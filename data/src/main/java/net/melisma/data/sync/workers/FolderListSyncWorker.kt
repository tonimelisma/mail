package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.MailFolder
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.data.mapper.toEntity
import timber.log.Timber
import java.util.UUID

@HiltWorker
class FolderSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val database: AppDatabase,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val folderDao: FolderDao
) : CoroutineWorker(appContext, workerParams) {

    // Helper function to identify well-known Gmail folder IDs
    // private fun isWellKnownGmailFolderId(folderIdFromApi: String): Boolean { // REMOVED
    //     return folderIdFromApi in setOf(
    //         "INBOX",
    //         "SENT",
    //         "DRAFTS",
    //         "TRASH",
    //         "SPAM",
    //         "IMPORTANT",
    //         "STARRED",
    //         "SCHEDULED",
    //         "ALL",
    //         "ARCHIVE" // Common Gmail system labels/folders
    //         // CHAT is often a label as well, but might not be a "folder" in all contexts.
    //         // Check specific Gmail API behavior if CHAT folders are needed.
    //     )
    // }

    override suspend fun doWork(): Result {
        val accountId = workerParams.inputData.getString(KEY_ACCOUNT_ID)
            ?: return Result.failure()

        Timber.d("Starting folder list sync for accountId: $accountId")

        try {
            val apiService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (apiService == null) {
                Timber.e("MailApiService not found for account $accountId. Aborting folder list sync.")
                return Result.failure()
            }

            val syncResult = apiService.syncFolders(accountId, null)

            if (syncResult.isSuccess) {
                val pagedResponse = syncResult.getOrThrow()
                val serverFolders = pagedResponse.newOrUpdatedItems

                database.withTransaction {
                    val localFolders = folderDao.getFoldersForAccount(accountId).first()
                    val localFolderMap = localFolders.associateBy { it.remoteId }
                    val serverFolderMap = serverFolders.associateBy { it.id }

                    // Delete local folders that are no longer on the server
                    val foldersToDelete = localFolders.filter { it.remoteId !in serverFolderMap.keys }
                    if (foldersToDelete.isNotEmpty()) {
                        folderDao.deleteFolders(foldersToDelete)
                        Timber.d("Deleted ${foldersToDelete.size} stale folders for account $accountId.")
                    }

                    // Upsert folders from the server
                    val foldersToUpsert = serverFolders.map { serverFolder ->
                        val existingLocal = localFolderMap[serverFolder.id]
                        val localId = existingLocal?.id ?: java.util.UUID.randomUUID().toString()
                        serverFolder.toEntity(accountId, localId)
                    }
                    if (foldersToUpsert.isNotEmpty()) {
                        folderDao.insertOrUpdateFolders(foldersToUpsert)
                        Timber.d("Upserted ${foldersToUpsert.size} server folders for account $accountId.")
                    }
                }
                
                accountDao.updateFolderListSyncSuccess(accountId, System.currentTimeMillis())
                accountDao.updateFolderListSyncToken(accountId, pagedResponse.nextSyncToken)
                Timber.d("Successfully synced folders for account $accountId.")
                return Result.success()

            } else {
                val exception = syncResult.exceptionOrNull()
                val error = "API Error: ${exception?.message}"
                Timber.e(exception, "Failed to sync folders for account $accountId: $error")
                accountDao.updateFolderListSyncError(accountId, error)
                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(accountId, true)
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            val error = "Worker Error: ${e.message}"
            Timber.e(e, "Error syncing folders for account $accountId: $error")
            accountDao.updateFolderListSyncError(accountId, error)
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                accountDao.setNeedsReauthentication(accountId, true)
            }
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "FolderListSyncWorker"
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_ERROR_MESSAGE = "ERROR_MESSAGE"
        // Add providerType if needed from inputData, though ideally fetched from AccountRepository
        // const val KEY_ACCOUNT_PROVIDER_TYPE = "ACCOUNT_PROVIDER_TYPE" 
    }
}
