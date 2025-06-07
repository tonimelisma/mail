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
import net.melisma.core_db.dao.RemoteKeyDao
import net.melisma.core_db.entity.RemoteKeyEntity
import net.melisma.data.mapper.toEntity
import timber.log.Timber
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.preferences.InitialSyncDurationPreference
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.Date

@HiltWorker
class FolderContentSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val remoteKeyDao: RemoteKeyDao,
    private val userPreferencesRepository: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "FolderContentSyncWorker"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString("ACCOUNT_ID")
        val localFolderPrimaryKey = inputData.getString("FOLDER_ID")
        // folderRemoteIdToFetch is used if localFolder.remoteId is null, 
        // but we fetch localFolder first and use its remoteId.
        // This input param might be redundant if localFolder.remoteId is always populated by FolderListSyncWorker.
        val folderRemoteIdInput = inputData.getString("FOLDER_REMOTE_ID")

        if (accountId.isNullOrBlank() || localFolderPrimaryKey.isNullOrBlank()) {
            Timber.tag(TAG)
                .e("Required ID (ACCOUNT_ID or FOLDER_ID (local PK)) missing in inputData.")
            return Result.failure()
        }

        Timber.tag(TAG)
            .d("Worker started for accountId: $accountId, localFolderPK: $localFolderPrimaryKey")

        try {
            val localFolder = folderDao.getFolderByIdSuspend(localFolderPrimaryKey)
            if (localFolder == null) {
                Timber.tag(TAG)
                    .e("Local FolderEntity with PK '$localFolderPrimaryKey' for account '$accountId' not found. Cannot sync content. FolderListSyncWorker might have failed or not run yet.")
                return Result.failure()
            }

            val apiFolderIdToFetch =
                localFolder.remoteId ?: folderRemoteIdInput ?: localFolderPrimaryKey
            val currentMessageListSyncToken = localFolder.messageListSyncToken

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

            if (currentMessageListSyncToken != null) {
                // --- DELTA SYNC PATH ---
                Timber.tag(TAG)
                    .d("Attempting DELTA sync for folder $apiFolderIdToFetch using token: $currentMessageListSyncToken")
                val messagesDeltaResult = mailService.syncMessagesForFolder(
                    folderId = apiFolderIdToFetch,
                    syncToken = currentMessageListSyncToken,
                    maxResultsFromInterface = 25 // Changed parameter name to maxResultsFromInterface
                )

                if (messagesDeltaResult.isSuccess) {
                    val deltaData = messagesDeltaResult.getOrThrow()
                    Timber.tag(TAG)
                        .d("Delta sync fetched ${deltaData.newOrUpdatedItems.size} new/updated messages and ${deltaData.deletedItemIds.size} deletions for $apiFolderIdToFetch. Next token: ${deltaData.nextSyncToken}")

                    if (deltaData.newOrUpdatedItems.isNotEmpty()) {
                        val messageEntities = deltaData.newOrUpdatedItems.map {
                            it.toEntity(
                                accountId,
                                localFolderPrimaryKey
                            )
                        }
                        messageDao.insertOrUpdateMessages(messageEntities)
                        Timber.tag(TAG)
                            .d("Saved ${messageEntities.size} delta messages to DB for $localFolderPrimaryKey.")
                    }

                    if (deltaData.deletedItemIds.isNotEmpty()) {
                        val deletedCount =
                            messageDao.deleteMessagesByRemoteIds(deltaData.deletedItemIds)
                        Timber.tag(TAG)
                            .d("Deleted $deletedCount delta messages from DB for $localFolderPrimaryKey.")
                    }

                    folderDao.updateLastSuccessfulSync(
                        localFolderPrimaryKey,
                        System.currentTimeMillis(),
                        SyncStatus.SYNCED
                    )
                    folderDao.updateMessageListSyncToken(
                        localFolderPrimaryKey,
                        deltaData.nextSyncToken
                    )
                    // When delta sync occurs, it brings the folder fully up-to-date. Old pagination tokens are invalid.
                    folderDao.updatePagingTokens(
                        localFolderPrimaryKey,
                        nextPageToken = null,
                        lastFullContentSyncTimestamp = System.currentTimeMillis()
                    )
                    remoteKeyDao.insertOrReplace(
                        RemoteKeyEntity(
                            folderId = localFolderPrimaryKey,
                            nextPageToken = null,
                            prevPageToken = null
                        )
                    )

                    Timber.tag(TAG)
                        .d("Folder $localFolderPrimaryKey updated via DELTA sync. Next delta token: ${deltaData.nextSyncToken}.")
                    return Result.success()
                } else {
                    val exception = messagesDeltaResult.exceptionOrNull()
                    val errorMessage = exception?.message ?: "Failed delta sync for folder content"
                    Timber.tag(TAG).e(
                        exception,
                        "Error delta syncing folder $apiFolderIdToFetch: $errorMessage"
                    )
                    folderDao.updateSyncStatusAndError(
                        localFolderPrimaryKey,
                        SyncStatus.ERROR,
                        errorMessage
                    )
                    if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                        accountDao.setNeedsReauthentication(accountId, true)
                    }
                    return Result.failure()
                }
            } else {
                // --- FULL/PAGED SYNC PATH (Initial sync or if no delta token) ---
                Timber.tag(TAG)
                    .d("Attempting FULL/PAGED sync for folder $apiFolderIdToFetch. Page token from FolderEntity: ${localFolder.nextPageToken}")

                // Fetch user preference for initial sync duration
                val userPrefs = userPreferencesRepository.userPreferencesFlow.first()
                val initialSyncDays = userPrefs.initialSyncDurationDays
                var earliestTimestampForFilter: Long? = null

                if (initialSyncDays != InitialSyncDurationPreference.ALL_TIME.durationInDays) {
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.DAY_OF_YEAR, -initialSyncDays.toInt())
                    earliestTimestampForFilter = calendar.timeInMillis
                    Timber.tag(TAG).d("Initial sync duration set to $initialSyncDays days. Fetching messages after: ${Date(earliestTimestampForFilter)}")
                } else {
                    Timber.tag(TAG).d("Initial sync duration set to ALL TIME. No date filter will be applied.")
                }

                val messagesResult = mailService.getMessagesForFolder(
                    folderId = apiFolderIdToFetch,
                    maxResults = 25,
                    pageToken = localFolder.nextPageToken, // Use folder's current page token for initial/paged sync
                    earliestTimestampEpochMillis = earliestTimestampForFilter // Pass the calculated timestamp
                )

                if (messagesResult.isSuccess) {
                    val pagedResponse = messagesResult.getOrThrow()
                    val remoteMessages = pagedResponse.messages
                    Timber.tag(TAG)
                        .d("Full/Paged sync fetched ${remoteMessages.size} messages for $apiFolderIdToFetch. Next page token from API: ${pagedResponse.nextPageToken}")

                    if (localFolder.nextPageToken == null && remoteMessages.isNotEmpty()) {
                        // If it's the first page of a full sync, clear existing messages for this folder first.
                        // Only do this if we actually got messages, to avoid wiping a folder if the first page fetch is empty but there was older data.
                        messageDao.deleteMessagesForFolder(accountId, localFolderPrimaryKey)
                        Timber.tag(TAG)
                            .d("Cleared old messages for folder $localFolderPrimaryKey before inserting new page.")
                    }

                    if (remoteMessages.isNotEmpty()) {
                        val messageEntities =
                            remoteMessages.map { it.toEntity(accountId, localFolderPrimaryKey) }
                        messageDao.insertOrUpdateMessages(messageEntities)
                        Timber.tag(TAG)
                            .d("Saved ${messageEntities.size} full/paged messages to DB for $localFolderPrimaryKey.")
                    }

                    folderDao.updateLastSuccessfulSync(
                        localFolderPrimaryKey,
                        System.currentTimeMillis(),
                        SyncStatus.SYNCED
                    )
                    folderDao.updatePagingTokens(
                        folderId = localFolderPrimaryKey,
                        nextPageToken = pagedResponse.nextPageToken,
                        lastFullContentSyncTimestamp = if (pagedResponse.nextPageToken == null) System.currentTimeMillis() else localFolder.lastFullContentSyncTimestamp
                    )
                    remoteKeyDao.insertOrReplace(
                        RemoteKeyEntity(
                            folderId = localFolderPrimaryKey,
                            nextPageToken = pagedResponse.nextPageToken,
                            prevPageToken = localFolder.nextPageToken // The token we used for this fetch
                        )
                    )
                    Timber.tag(TAG)
                        .d("Folder $localFolderPrimaryKey updated via FULL/PAGED sync. Next page token: ${pagedResponse.nextPageToken}.")
                    return Result.success()
                } else {
                    val exception = messagesResult.exceptionOrNull()
                    val errorMessage =
                        exception?.message ?: "Failed full/paged sync for folder content"
                    Timber.tag(TAG).e(
                        exception,
                        "Error full/paged syncing folder $apiFolderIdToFetch: $errorMessage"
                    )
                    folderDao.updateSyncStatusAndError(
                        localFolderPrimaryKey,
                        SyncStatus.ERROR,
                        errorMessage
                    )
                    if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                        accountDao.setNeedsReauthentication(accountId, true)
                    }
                    return Result.failure()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "Error syncing folder content for accountId: $accountId, localFolderPK: $localFolderPrimaryKey"
            )
            // Ensure folderDao is called even with a general exception
            // localFolderPrimaryKey is guaranteed non-null here due to initial check
            try {
                folderDao.updateSyncStatusAndError(
                    localFolderPrimaryKey,
                    SyncStatus.ERROR,
                    e.message ?: "Unknown error during folder content sync worker"
                )
            } catch (dbExc: Exception) {
                Timber.tag(TAG)
                    .e(dbExc, "Failed to update folder sync status on general error path.")
            }

            // accountId is guaranteed non-null here
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
