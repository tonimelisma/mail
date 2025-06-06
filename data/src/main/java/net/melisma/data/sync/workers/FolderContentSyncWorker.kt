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
        val folderId = inputData.getString("FOLDER_ID")
        val folderRemoteId = inputData.getString("FOLDER_REMOTE_ID")

        if (accountId.isNullOrBlank() || folderId.isNullOrBlank() || folderRemoteId.isNullOrBlank()) {
            Timber.e("Required ID (accountId, folderId, or folderRemoteId) missing in inputData.")
            return Result.failure()
        }

        Timber.d("Worker started for accountId: $accountId, folderId: $folderId (Remote: $folderRemoteId)")

        try {
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("MailService not found for account $accountId. Failing folder content sync.")
                folderDao.updateSyncStatusAndError(
                    folderId,
                    SyncStatus.ERROR,
                    "Mail service not found for account"
                )
                return Result.failure()
            }

            val folderEntity = folderDao.getFolderByIdSuspend(folderId)
            val lastSyncTime = folderEntity?.lastSuccessfulSyncTimestamp
            Timber.d("$TAG: Current folder ($folderId) lastSuccessfulSyncTimestamp: $lastSyncTime. Will be used for delta sync in future API calls.")

            val messagesResult = mailService.getMessagesForFolder(
                folderId = folderRemoteId,
                activity = null,
                maxResults = 25,
                pageToken = folderEntity?.nextPageToken
            )

            if (messagesResult.isSuccess) {
                val pagedResponse = messagesResult.getOrThrow()
                val remoteMessages = pagedResponse.messages
                Timber.d("Fetched ${remoteMessages.size} messages from API for folder $folderRemoteId (local $folderId). Next page token from API: ${pagedResponse.nextPageToken}")

                if (remoteMessages.isNotEmpty()) {
                    val messageEntities = remoteMessages.map { it.toEntity(accountId, folderId) }
                    messageDao.insertOrUpdateMessages(messageEntities)
                    Timber.d("Saved ${messageEntities.size} messages to DB for folder $folderId.")
                }

                folderDao.updateLastSuccessfulSync(
                    folderId,
                    System.currentTimeMillis(),
                    SyncStatus.SYNCED
                )
                folderDao.updatePagingTokens(
                    folderId = folderId,
                    nextPageToken = pagedResponse.nextPageToken,
                    lastFullContentSyncTimestamp = if (pagedResponse.nextPageToken == null) System.currentTimeMillis() else folderEntity?.lastFullContentSyncTimestamp
                )
                Timber.d("Updated folder $folderId sync status to SYNCED and nextPageToken to ${pagedResponse.nextPageToken}.")
                return Result.success()
            } else {
                val exception = messagesResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to fetch folder content"
                Timber.e(exception, "Error syncing folder content for $folderId: $errorMessage")
                folderDao.updateSyncStatusAndError(folderId, SyncStatus.ERROR, errorMessage)

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to folder content sync failure.")
                    try {
                        accountDao.setNeedsReauthentication(accountId, true)
                    } catch (dbException: Exception) {
                        Timber.e(
                            dbException,
                            "$TAG: Failed to mark account $accountId for re-authentication in DB."
                        )
                    }
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing folder content for accountId: $accountId, folderId: $folderId")
            folderDao.updateSyncStatusAndError(
                folderId,
                SyncStatus.ERROR,
                e.message ?: "Unknown error during folder content sync"
            )
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                Timber.w("$TAG: Marking account $accountId for re-authentication due to outer exception during folder content sync.")
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
