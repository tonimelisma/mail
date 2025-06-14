package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.MessageFolderJunctionDao
import net.melisma.core_db.dao.RemoteKeyDao
import net.melisma.core_db.entity.RemoteKeyEntity
import net.melisma.data.mapper.toEntity
import timber.log.Timber

@HiltWorker
class FolderContentSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: AppDatabase,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val remoteKeyDao: RemoteKeyDao,
    private val networkMonitor: NetworkMonitor,
    private val messageFolderJunctionDao: MessageFolderJunctionDao,
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "FolderContentSyncWorker"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val folderId = inputData.getString(KEY_FOLDER_ID)

        if (accountId.isNullOrBlank() || folderId.isNullOrBlank()) {
            Timber.e("$TAG: Missing required IDs in input data.")
            return Result.failure()
        }
        
        if (!networkMonitor.isOnline.first()) {
            Timber.i("$TAG: Network offline. Retrying later.")
            return Result.retry()
        }

        Timber.d("$TAG: Starting content sync for folder $folderId.")

        try {
            val localFolder = folderDao.getFolderByIdSuspend(folderId)
                ?: return Result.failure().also { Timber.e("$TAG: Folder with local ID $folderId not found.") }

            val apiFolderId = localFolder.remoteId ?: return Result.failure()
                .also { Timber.e("$TAG: Folder $folderId has no remote ID.") }
            
            folderDao.updateSyncStatus(folderId, EntitySyncStatus.PENDING_DOWNLOAD)

            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("$TAG: MailApiService not available for account $accountId. Aborting sync.")
                return Result.failure()
            }

            val response = mailService.getMessagesForFolder(
                folderId = apiFolderId,
                maxResults = 25,
                pageToken = null
            )

            if (response.isSuccess) {
                val pagedResponse = response.getOrThrow()
                val serverMessages = pagedResponse.messages

                database.withTransaction {
                    // Clear existing messages and remote key to ensure a fresh start
                    messageDao.deleteMessagesByFolder(folderId)
                    messageFolderJunctionDao.deleteByFolder(folderId)
                    remoteKeyDao.deleteRemoteKeyForFolder(folderId)

                    val messageEntities = serverMessages.map { it.toEntity(accountId) }
                    messageDao.insertOrUpdateMessages(messageEntities)
                    val junctions = messageEntities.map { me ->
                        net.melisma.core_db.entity.MessageFolderJunction(me.id, folderId)
                    }
                    messageFolderJunctionDao.insertAll(junctions)

                    // Set the next page token for the mediator
                    val nextKey = pagedResponse.nextPageToken
                    if (nextKey != null) {
                        remoteKeyDao.insertOrReplace(
                            RemoteKeyEntity(
                                folderId = folderId,
                                nextPageToken = nextKey,
                                prevPageToken = null
                            )
                        )
                    }
                    
                    folderDao.updateLastSuccessfulSync(folderId, System.currentTimeMillis())
                }

                Timber.i("$TAG: Successfully synced ${serverMessages.size} messages for folder $folderId.")
                return Result.success()
            } else {
                val exception = response.exceptionOrNull()
                val error = "API Error: ${exception?.message}"
                Timber.e(exception, "$TAG: Failed to sync content for folder $folderId: $error")
                folderDao.updateSyncStatusAndError(folderId, EntitySyncStatus.ERROR, error)
                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(accountId, true)
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            val error = "Worker Error: ${e.message}"
            Timber.e(e, "$TAG: Error syncing content for folder $folderId: $error")
            folderDao.updateSyncStatusAndError(folderId, EntitySyncStatus.ERROR, error)
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                accountDao.setNeedsReauthentication(accountId, true)
            }
            return Result.failure()
        }
    }
    
    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_FOLDER_ID = "FOLDER_ID"
        const val KEY_IS_MANUAL_REFRESH = "IS_MANUAL_REFRESH"
    }
} 