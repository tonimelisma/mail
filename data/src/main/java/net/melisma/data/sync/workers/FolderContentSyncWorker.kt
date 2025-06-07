package net.melisma.data.sync.workers

import android.content.Context
// import android.net.ConnectivityManager // No longer needed
// import android.net.NetworkCapabilities // No longer needed
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.melisma.core_data.connectivity.NetworkMonitor // Added
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.RemoteKeyDao
import net.melisma.core_db.entity.MessageEntity
import net.melisma.core_db.entity.RemoteKeyEntity
import net.melisma.data.mapper.toEntity
import timber.log.Timber
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.preferences.InitialSyncDurationPreference
import net.melisma.core_data.preferences.DownloadPreference
import net.melisma.core_data.model.Message as ApiMessage
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.Date

@HiltWorker
class FolderContentSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val remoteKeyDao: RemoteKeyDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    // private val connectivityManager: ConnectivityManager, // Replaced
    private val networkMonitor: NetworkMonitor, // Injected
    private val workManager: WorkManager
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "FolderContentSyncWorker"

    // Removed local isOnline() and isWifiConnected() methods

    private suspend fun enqueueBodyDownloadIfAppropriate(messageEntity: MessageEntity, bodyPreference: DownloadPreference) {
        val currentIsOnline = networkMonitor.isOnline.first()
        val currentIsWifi = networkMonitor.isWifiConnected.first()

        val shouldDownload =
            (bodyPreference == DownloadPreference.ALWAYS && currentIsOnline) ||
            (bodyPreference == DownloadPreference.ON_WIFI && currentIsWifi)

        if (shouldDownload) {
            Timber.d("$TAG: Enqueuing MessageBodyDownloadWorker for messageId: ${messageEntity.id}, preference: $bodyPreference")
            val workRequest = OneTimeWorkRequestBuilder<MessageBodyDownloadWorker>()
                .setInputData(workDataOf(
                    MessageBodyDownloadWorker.KEY_ACCOUNT_ID to messageEntity.accountId,
                    MessageBodyDownloadWorker.KEY_MESSAGE_ID to messageEntity.id
                ))
                .build()
            workManager.enqueueUniqueWork(
                "message-body-download-${messageEntity.id}",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        } else {
            Timber.d("$TAG: Skipping body download for messageId: ${messageEntity.id}, preference: $bodyPreference, isOnline: $currentIsOnline, isWifi: $currentIsWifi")
        }
    }

    private suspend fun enqueueAttachmentDownloadsIfAppropriate(
        apiMessage: ApiMessage,
        accountId: String,
        attachmentPreference: DownloadPreference
    ) {
        if (apiMessage.attachments.isEmpty()) return

        val currentIsOnline = networkMonitor.isOnline.first()
        val currentIsWifi = networkMonitor.isWifiConnected.first()

        val shouldDownloadAttachments =
            (attachmentPreference == DownloadPreference.ALWAYS && currentIsOnline) ||
            (attachmentPreference == DownloadPreference.ON_WIFI && currentIsWifi)

        if (shouldDownloadAttachments) {
            apiMessage.attachments.forEach { attachment ->
                Timber.d("$TAG: Enqueuing AttachmentDownloadWorker for msg: ${apiMessage.id}, att: ${attachment.id}, pref: $attachmentPreference")
                val workRequest = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                    .setInputData(workDataOf(
                        AttachmentDownloadWorker.KEY_ACCOUNT_ID to accountId,
                        AttachmentDownloadWorker.KEY_MESSAGE_ID to apiMessage.id,
                        AttachmentDownloadWorker.KEY_ATTACHMENT_ID to attachment.id,
                        AttachmentDownloadWorker.KEY_ATTACHMENT_NAME to attachment.fileName
                    ))
                    .build()
                workManager.enqueueUniqueWork(
                    "attachment-download-${attachment.id}",
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
            }
        } else {
            Timber.d("$TAG: Skipping attachment downloads for messageId: ${apiMessage.id}, pref: $attachmentPreference, isOnline: $currentIsOnline, isWifi: $currentIsWifi")
        }
    }

    override suspend fun doWork(): Result {
        val accountId = inputData.getString("ACCOUNT_ID")
        val localFolderPrimaryKey = inputData.getString("FOLDER_ID")
        val folderRemoteIdInput = inputData.getString("FOLDER_REMOTE_ID")

        if (accountId.isNullOrBlank() || localFolderPrimaryKey.isNullOrBlank()) {
            Timber.tag(TAG).e("Required ID (ACCOUNT_ID or FOLDER_ID (local PK)) missing in inputData.")
            return Result.failure()
        }

        Timber.tag(TAG).d("Worker started for accountId: $accountId, localFolderPK: $localFolderPrimaryKey")

        // Top-level try-catch for the entire worker execution
        try {
            // Network check at the beginning of the worker. If offline, retry might be appropriate
            // depending on how this worker is scheduled (e.g. if it's a periodic sync or one-off)
            // For now, let it proceed, and individual download enqueues will check network.
            // if (!networkMonitor.isOnline.first()) {
            //     Timber.tag(TAG).i("Device is offline. Retrying FolderContentSyncWorker for $localFolderPrimaryKey later.")
            //     return Result.retry()
            // }

            val localFolder = folderDao.getFolderByIdSuspend(localFolderPrimaryKey)
            if (localFolder == null) {
                Timber.tag(TAG).e("Local FolderEntity with PK '$localFolderPrimaryKey' for account '$accountId' not found.")
                return Result.failure()
            }

            val apiFolderIdToFetch = localFolder.remoteId ?: folderRemoteIdInput ?: localFolderPrimaryKey
            val currentMessageListSyncToken = localFolder.messageListSyncToken

            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.tag(TAG).e("MailService not found for account $accountId.")
                folderDao.updateSyncStatusAndError(localFolderPrimaryKey, SyncStatus.ERROR, "Mail service not found for account")
                return Result.failure()
            }

            if (currentMessageListSyncToken != null) {
                // --- DELTA SYNC PATH ---
                Timber.tag(TAG).d("Attempting DELTA sync for folder $apiFolderIdToFetch using token: $currentMessageListSyncToken")
                val messagesDeltaResult = mailService.syncMessagesForFolder(
                    folderId = apiFolderIdToFetch,
                    syncToken = currentMessageListSyncToken,
                    maxResultsFromInterface = 25
                )

                if (messagesDeltaResult.isSuccess) {
                    val deltaData = messagesDeltaResult.getOrThrow()
                    Timber.tag(TAG).d("Delta sync fetched ${deltaData.newOrUpdatedItems.size} new/updated messages and ${deltaData.deletedItemIds.size} deletions for $apiFolderIdToFetch. Next token: ${deltaData.nextSyncToken}")

                    if (deltaData.newOrUpdatedItems.isNotEmpty()) {
                        val apiMessagesFromDelta = deltaData.newOrUpdatedItems
                        val messageEntities = apiMessagesFromDelta.map { it.toEntity(accountId, localFolderPrimaryKey) }
                        messageDao.insertOrUpdateMessages(messageEntities)
                        Timber.tag(TAG).d("Saved ${messageEntities.size} delta messages to DB for $localFolderPrimaryKey.")

                        val userPrefsForDelta = userPreferencesRepository.userPreferencesFlow.first()
                        apiMessagesFromDelta.zip(messageEntities).forEach { (apiMsg, entity) ->
                            enqueueBodyDownloadIfAppropriate(entity, userPrefsForDelta.bodyDownloadPreference)
                            enqueueAttachmentDownloadsIfAppropriate(apiMsg, accountId, userPrefsForDelta.attachmentDownloadPreference)
                        }
                    }

                    if (deltaData.deletedItemIds.isNotEmpty()) {
                        val deletedCount = messageDao.deleteMessagesByRemoteIds(deltaData.deletedItemIds)
                        Timber.tag(TAG).d("Deleted $deletedCount delta messages from DB for $localFolderPrimaryKey.")
                    }

                    folderDao.updateLastSuccessfulSync(localFolderPrimaryKey, System.currentTimeMillis(), SyncStatus.SYNCED)
                    folderDao.updateMessageListSyncToken(localFolderPrimaryKey, deltaData.nextSyncToken)
                    folderDao.updatePagingTokens(localFolderPrimaryKey, nextPageToken = null, lastFullContentSyncTimestamp = System.currentTimeMillis())
                    remoteKeyDao.insertOrReplace(RemoteKeyEntity(folderId = localFolderPrimaryKey, nextPageToken = null, prevPageToken = null))

                    Timber.tag(TAG).d("Folder $localFolderPrimaryKey updated via DELTA sync. Next delta token: ${deltaData.nextSyncToken}.")
                    return Result.success()
                } else {
                    val exception = messagesDeltaResult.exceptionOrNull()
                    val errorMessage = exception?.message ?: "Failed delta sync for folder content"
                    Timber.tag(TAG).e(exception, "Error delta syncing folder $apiFolderIdToFetch: $errorMessage")
                    folderDao.updateSyncStatusAndError(localFolderPrimaryKey, SyncStatus.ERROR, errorMessage)
                    if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                        accountDao.setNeedsReauthentication(accountId, true)
                    }
                    return Result.failure()
                }
            } else {
                // --- FULL/PAGED SYNC PATH (Initial sync or if no delta token) ---
                Timber.tag(TAG).d("Attempting FULL/PAGED sync for folder $apiFolderIdToFetch. Page token from FolderEntity: ${localFolder.nextPageToken}")

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
                    pageToken = localFolder.nextPageToken,
                    earliestTimestampEpochMillis = earliestTimestampForFilter
                )

                if (messagesResult.isSuccess) {
                    val pagedResponse = messagesResult.getOrThrow()
                    val remoteMessagesApi = pagedResponse.messages
                    Timber.tag(TAG).d("Full/Paged sync fetched ${remoteMessagesApi.size} messages for $apiFolderIdToFetch. Next page token from API: ${pagedResponse.nextPageToken}")

                    if (localFolder.nextPageToken == null && remoteMessagesApi.isNotEmpty()) {
                        messageDao.deleteMessagesForFolder(accountId, localFolderPrimaryKey)
                        Timber.tag(TAG).d("Cleared old messages for folder $localFolderPrimaryKey before inserting new page.")
                    }

                    if (remoteMessagesApi.isNotEmpty()) {
                        val messageEntitiesFromFullSync = remoteMessagesApi.map { it.toEntity(accountId, localFolderPrimaryKey) }
                        messageDao.insertOrUpdateMessages(messageEntitiesFromFullSync)
                        Timber.tag(TAG).d("Saved ${messageEntitiesFromFullSync.size} full/paged messages to DB for $localFolderPrimaryKey.")

                        remoteMessagesApi.zip(messageEntitiesFromFullSync).forEach { (apiMsg, entity) ->
                            enqueueBodyDownloadIfAppropriate(entity, userPrefs.bodyDownloadPreference)
                            enqueueAttachmentDownloadsIfAppropriate(apiMsg, accountId, userPrefs.attachmentDownloadPreference)
                        }
                    }

                    folderDao.updateLastSuccessfulSync(localFolderPrimaryKey, System.currentTimeMillis(), SyncStatus.SYNCED)
                    if (pagedResponse.nextPageToken != null) {
                        folderDao.updatePagingTokens(localFolderPrimaryKey, nextPageToken = pagedResponse.nextPageToken, lastFullContentSyncTimestamp = null) // Keep lastFullContentSyncTimestamp null until full sync completes
                        remoteKeyDao.insertOrReplace(RemoteKeyEntity(folderId = localFolderPrimaryKey, nextPageToken = pagedResponse.nextPageToken, prevPageToken = localFolder.nextPageToken))
                        Timber.tag(TAG).d("Folder $localFolderPrimaryKey page synced. Enqueuing next page. Next API token: ${pagedResponse.nextPageToken}")
                        // Enqueue next page sync for this folder
                        val nextPageWorkRequest = OneTimeWorkRequestBuilder<FolderContentSyncWorker>()
                            .setInputData(workDataOf(
                                "ACCOUNT_ID" to accountId,
                                "FOLDER_ID" to localFolderPrimaryKey,
                                "FOLDER_REMOTE_ID" to apiFolderIdToFetch // Pass remote ID too
                            ))
                            .build()
                        workManager.enqueueUniqueWork(
                            "folder-content-sync-${localFolderPrimaryKey}-page-${pagedResponse.nextPageToken?.replace("/", "-")?.take(20) ?: "final"}",
                            ExistingWorkPolicy.REPLACE, // Replace if it was already scheduled, e.g. from a previous partial sync
                            nextPageWorkRequest
                        )
                    } else {
                        // This was the last page for the full sync
                        folderDao.updatePagingTokens(localFolderPrimaryKey, nextPageToken = null, lastFullContentSyncTimestamp = System.currentTimeMillis())
                        remoteKeyDao.insertOrReplace(RemoteKeyEntity(folderId = localFolderPrimaryKey, nextPageToken = null, prevPageToken = localFolder.nextPageToken))
                        Timber.tag(TAG).d("Folder $localFolderPrimaryKey full sync completed (last page). Clearing next page token.")
                    }
                    return Result.success()
                } else {
                    val exception = messagesResult.exceptionOrNull()
                    val errorMessage = exception?.message ?: "Failed paged sync for folder content"
                    Timber.tag(TAG).e(exception, "Error paged syncing folder $apiFolderIdToFetch: $errorMessage")
                    folderDao.updateSyncStatusAndError(localFolderPrimaryKey, SyncStatus.ERROR, errorMessage)
                    if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                        accountDao.setNeedsReauthentication(accountId, true)
                    }
                    return Result.failure()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unhandled exception in FolderContentSyncWorker for account $accountId, folder $localFolderPrimaryKey")
            // Attempt to update folder status to error, if possible
            try {
                folderDao.updateSyncStatusAndError(localFolderPrimaryKey, SyncStatus.ERROR, e.message ?: "Unhandled worker error")
            } catch (dbError: Exception) {
                Timber.tag(TAG).e(dbError, "Failed to update folder status on unhandled worker error.")
            }
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                try {
                    accountDao.setNeedsReauthentication(accountId, true)
                } catch (dbError: Exception) {
                    Timber.tag(TAG).e(dbError, "Failed to mark account for re-auth on unhandled worker error.")
                }
            }
            return Result.failure() // Propagate failure
        }
    }
}
