package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.repository.AccountRepository
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
    private val accountRepository: AccountRepository,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao
) : CoroutineWorker(appContext, workerParams) {

    // Helper function to identify well-known Gmail folder IDs
    private fun isWellKnownGmailFolderId(folderIdFromApi: String): Boolean {
        return folderIdFromApi in setOf(
            "INBOX",
            "SENT",
            "DRAFTS",
            "TRASH",
            "SPAM",
            "IMPORTANT",
            "STARRED",
            "SCHEDULED",
            "ALL",
            "ARCHIVE" // Common Gmail system labels/folders
            // CHAT is often a label as well, but might not be a "folder" in all contexts.
            // Check specific Gmail API behavior if CHAT folders are needed.
        )
    }

    override suspend fun doWork(): Result {
        val accountId = workerParams.inputData.getString(KEY_ACCOUNT_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Account ID is missing"))

        Timber.d("doWork: Starting folder list sync for accountId: $accountId")

        val account = accountRepository.getAccountById(accountId).firstOrNull()
        if (account == null) {
            Timber.e("doWork: Account not found for ID: $accountId")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Account not found"))
        }

        // It's assumed account.providerType exists and is like "GMAIL" or "MICROSOFT_GRAPH"
        // This might come from AccountEntity's mapping to the Account domain model.
        val providerType = account.providerType // Confirmed Account.kt has this

        val apiService = mailApiServiceSelector.getServiceByAccountId(accountId)
        if (apiService == null) {
            Timber.e("doWork: No API service found for account ID: $accountId, providerType: $providerType")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unsupported provider type or account issue"))
        }

        try {
            val remoteFoldersResult = apiService.getMailFolders(null, accountId)

            if (remoteFoldersResult.isSuccess) {
                val remoteFolders = remoteFoldersResult.getOrThrow()
                database.withTransaction {
                    // Delete all existing folders for this account first
                    database.folderDao().deleteAllFoldersForAccount(accountId)

                    val folderEntities = remoteFolders.map { mailFolder: MailFolder ->
                        val remoteApiId =
                            mailFolder.id // ID from the API (e.g., "SENT", "INBOX", or a GUID)
                        val primaryKeyForDb: String

                        if (providerType == Account.PROVIDER_TYPE_GOOGLE) {
                            if (isWellKnownGmailFolderId(remoteApiId)) {
                                // For well-known Gmail folders, use their known remote ID (e.g., "SENT") as the local primary key.
                                primaryKeyForDb = remoteApiId
                            } else {
                                // For user-created Gmail labels (which have opaque API IDs like "Label_123"), generate a UUID.
                                // Their remoteApiId will be stored in FolderEntity.remoteId by the toEntity mapper.
                                primaryKeyForDb = UUID.randomUUID().toString()
                            }
                        } else {
                            // For other providers (e.g., Microsoft Graph), their API folder IDs (typically GUIDs)
                            // are suitable as local primary keys. If they can be non-unique across accounts (unlikely for folder IDs)
                            // or not stable, a UUID approach might be safer, but generally remote IDs are fine here.
                            primaryKeyForDb = remoteApiId
                        }
                        mailFolder.toEntity(accountId, primaryKeyForDb)
                    }
                    if (folderEntities.isNotEmpty()) {
                        database.folderDao().insertOrUpdateFolders(folderEntities)
                    }
                }
                Timber.d("doWork: Successfully synced ${remoteFolders.size} folders for account $accountId")
                accountDao.updateFolderListSyncSuccess(accountId, System.currentTimeMillis())
                return Result.success()
            } else {
                val exception = remoteFoldersResult.exceptionOrNull()
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
