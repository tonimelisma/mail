package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.data.mapper.toEntity
import timber.log.Timber

@HiltWorker
class FolderContentSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "FolderContentSyncWorker"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString("ACCOUNT_ID")
        val localFolderPrimaryKey = inputData.getString("FOLDER_ID")
        val folderRemoteIdToFetch = inputData.getString("FOLDER_REMOTE_ID")

        if (accountId.isNullOrBlank() || localFolderPrimaryKey.isNullOrBlank() || folderRemoteIdToFetch.isNullOrBlank()) {
            Timber.tag(TAG)
                .e("Required ID (ACCOUNT_ID, FOLDER_ID (local PK), or FOLDER_REMOTE_ID) missing in inputData.")
            return Result.failure()
        }

        Timber.tag(TAG)
            .d("Worker started for accountId: $accountId, localFolderPK: $localFolderPrimaryKey, remoteId: $folderRemoteIdToFetch")

        try {
            val folderEntity = folderDao.getFolderByIdSuspend(localFolderPrimaryKey)
            if (folderEntity == null) {
                Timber.tag(TAG)
                    .e("Local FolderEntity with PK '$localFolderPrimaryKey' for account '$accountId' not found. Cannot sync content. FolderListSyncWorker might have failed or not run yet.")
                return Result.failure()
            }

            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.tag(TAG)
                    .e("MailService not found for account $accountId. Failing folder content sync.")
                folderDao.updateSyncStatusAndError(
                    localFolderPrimaryKey,
                    SyncStatus.ERROR,
                    "Mail service not found for account"
                )
                return Result.failure()
            }

            val messagesResult = mailService.getMessagesForFolder(
                folderId = folderRemoteIdToFetch,
                activity = null,
                maxResults = 25,
                pageToken = folderEntity.nextPageToken
            )

            if (messagesResult.isSuccess) {
                val pagedResponse = messagesResult.getOrThrow()
                val remoteMessages = pagedResponse.messages
                Timber.tag(TAG)
                    .d("Fetched ${remoteMessages.size} messages from API for remote folder $folderRemoteIdToFetch (local PK $localFolderPrimaryKey). Next page token from API: ${pagedResponse.nextPageToken}")

                if (remoteMessages.isNotEmpty()) {
                    val messageEntities =
                        remoteMessages.map { it.toEntity(accountId, localFolderPrimaryKey) }
                    messageDao.insertOrUpdateMessages(messageEntities)
                    Timber.tag(TAG)
                        .d("Saved ${messageEntities.size} messages to DB for local folder PK $localFolderPrimaryKey.")
                }

                folderDao.updateLastSuccessfulSync(
                    localFolderPrimaryKey,
                    System.currentTimeMillis(),
                    SyncStatus.SYNCED
                )
                folderDao.updatePagingTokens(
                    folderId = localFolderPrimaryKey,
                    nextPageToken = pagedResponse.nextPageToken,
                    lastFullContentSyncTimestamp = if (pagedResponse.nextPageToken == null) System.currentTimeMillis() else folderEntity.lastFullContentSyncTimestamp
                )
                Timber.tag(TAG)
                    .d("Updated folder $localFolderPrimaryKey sync status to SYNCED and nextPageToken to ${pagedResponse.nextPageToken}.")
                return Result.success()
            } else {
                val exception = messagesResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to fetch folder content"
                Timber.tag(TAG).e(
                    exception,
                    "Error syncing folder content for $localFolderPrimaryKey: $errorMessage"
                )
                folderDao.updateSyncStatusAndError(
                    localFolderPrimaryKey,
                    SyncStatus.ERROR,
                    errorMessage
                )

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    Timber.tag(TAG)
                        .w("Marking account $accountId for re-authentication due to folder content sync failure.")
                    try {
                        accountDao.setNeedsReauthentication(accountId, true)
                    } catch (dbException: Exception) {
                        Timber.e(
                            dbException,
                            "$TAG: Failed to mark account $accountId for re-auth in DB (outer exception)."
                        )
                    }
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "Error syncing folder content for accountId: $accountId, localFolderPK: $localFolderPrimaryKey"
            )
            folderDao.updateSyncStatusAndError(
                localFolderPrimaryKey,
                SyncStatus.ERROR,
                e.message ?: "Unknown error during folder content sync"
            )
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                Timber.tag(TAG)
                    .w("Marking account $accountId for re-authentication due to outer exception during folder content sync.")
                try {
                    accountDao.setNeedsReauthentication(accountId, true)
                } catch (dbException: Exception) {
                    Timber.e(
                        dbException,
                        "$TAG: Failed to mark account $accountId for re-auth in DB (outer exception)."
                    )
                }
            }
            return Result.failure()
        }
    }
}
