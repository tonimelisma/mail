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
import net.melisma.data.mapper.toEntity
import timber.log.Timber
import java.util.UUID

@HiltWorker
class FolderListSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val database: AppDatabase,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao
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
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Account ID is missing"))

        Timber.d("doWork: Starting folder list sync for accountId: $accountId")

        val accountEntity = accountDao.getAccountByIdSuspend(accountId)
        if (accountEntity == null) {
            Timber.e("doWork: AccountEntity not found for ID: $accountId")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Account not found"))
        }
        // For a full sync, we effectively ignore the stored sync token by passing null,
        // or let the API helper decide. For Gmail, it always fetches all.
        // For Graph, passing null to syncFolders implies starting a new delta sequence (i.e., get all).
        val syncTokenForFullRefresh = null // Explicitly null for a full refresh behavior
        val providerType = accountEntity.providerType

        val apiService = mailApiServiceSelector.getServiceByAccountId(accountId)
        if (apiService == null) {
            Timber.e("doWork: No API service found for account ID: $accountId, providerType: $providerType")
            accountDao.updateFolderListSyncError(
                accountId,
                "FolderListSync: API service not found for provider $providerType"
            )
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unsupported provider type or account issue"))
        }

        try {
            val deltaSyncResult = apiService.syncFolders(accountId, syncTokenForFullRefresh)

            if (deltaSyncResult.isSuccess) {
                val deltaData = deltaSyncResult.getOrThrow()
                val foldersFromApi = deltaData.newOrUpdatedItems
                val remoteIdsFromApi =
                    foldersFromApi.mapNotNull { it.id }.toSet() // mailFolder.id is the remoteId

                database.withTransaction {
                    // 1. Upsert folders received from API
                    if (foldersFromApi.isNotEmpty()) {
                        val folderEntities = foldersFromApi.map { mailFolder: MailFolder ->
                            val primaryKeyForDb: String = UUID.randomUUID().toString()
                            mailFolder.toEntity(accountId, primaryKeyForDb)
                        }
                        database.folderDao().insertOrUpdateFolders(folderEntities)
                        Timber.d("doWork: Upserted ${folderEntities.size} folders for account $accountId from API list.")
                    }

                    // 2. Identify and delete stale local folders
                    val localFolders = database.folderDao().getFoldersForAccount(accountId)
                        .first() // Get current local state
                    val staleRemoteIds =
                        localFolders.mapNotNull { it.remoteId }.filter { it !in remoteIdsFromApi }

                    if (staleRemoteIds.isNotEmpty()) {
                        val deletedStaleCount =
                            database.folderDao().deleteFoldersByRemoteIds(accountId, staleRemoteIds)
                        Timber.d("doWork: Deleted $deletedStaleCount stale local folders for account $accountId.")
                    }

                    // 3. Process explicit deletions from delta, if any (belt and suspenders)
                    //    This might be redundant if the above diffing is comprehensive, but harmless.
                    if (deltaData.deletedItemIds.isNotEmpty()) {
                        // Ensure these IDs are distinct from those just deleted as stale, though unlikely to overlap significantly.
                        val explicitlyDeletedRemoteIds =
                            deltaData.deletedItemIds.filter { it !in staleRemoteIds }
                        if (explicitlyDeletedRemoteIds.isNotEmpty()) {
                            val deletedCount = database.folderDao()
                                .deleteFoldersByRemoteIds(accountId, explicitlyDeletedRemoteIds)
                            Timber.d("doWork: Deleted $deletedCount folders for account $accountId based on explicit delta.deletedItemIds.")
                        }
                    }
                }
                // Update the sync token for the next delta sync (even if we forced a full refresh, API provides next token)
                accountDao.updateFolderListSyncToken(accountId, deltaData.nextSyncToken)
                accountDao.updateFolderListSyncSuccess(accountId, System.currentTimeMillis())
                Timber.d("doWork: Successfully full-synced folders for account $accountId. Next token: ${deltaData.nextSyncToken}")
                return Result.success()
            } else {
                val exception = deltaSyncResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to fetch folders from API"
                Timber.e(
                    exception,
                    "Failed to fetch folders from API for account $accountId: $errorMessage"
                )
                accountDao.updateFolderListSyncError(accountId, "FolderListSync: $errorMessage")

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to folder list sync failure.")
                    accountDao.setNeedsReauthentication(accountId, true)
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing folders for accountId: $accountId")
            accountDao.updateFolderListSyncError(
                accountId,
                "FolderListSync: ${e.message ?: "Unknown error"}"
            )
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                Timber.w("$TAG: Marking account $accountId for re-authentication due to outer exception during folder list sync.")
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

    companion object {
        private const val TAG = "FolderListSyncWorker"
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_ERROR_MESSAGE = "ERROR_MESSAGE"
        // Add providerType if needed from inputData, though ideally fetched from AccountRepository
        // const val KEY_ACCOUNT_PROVIDER_TYPE = "ACCOUNT_PROVIDER_TYPE" 
    }
}
