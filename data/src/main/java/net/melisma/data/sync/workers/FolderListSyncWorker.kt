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
import net.melisma.data.mapper.toEntity
import timber.log.Timber

@HiltWorker
class FolderListSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val folderDao: FolderDao,
    private val accountDao: AccountDao,
    private val mailApiServiceSelector: MailApiServiceSelector
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "FolderListSyncWorker"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString("ACCOUNT_ID")
        if (accountId.isNullOrBlank()) {
            Timber.e("Account ID missing in inputData. Cannot sync folders.")
            return Result.failure()
        }

        Timber.d("Worker started for accountId: $accountId")

        try {
            // Get MailApiService for accountId
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("MailService not found for account $accountId. Failing folder list sync.")
                accountDao.updateAccountSyncError(
                    accountId,
                    "FolderListSync: MailService not found"
                )
                return Result.failure()
            }

            // TODO: P3_SYNC - In API call, pass lastSuccessfulSyncTimestamp (from Account or Folder metadata) to fetch only new/changed items.
            // val accountEntity = accountDao.getAccountByIdSuspend(accountId) // Needs AccountDao injection
            // val lastSyncTime = accountEntity?.lastSuccessfulSyncTimestamp

            // Fetch folder list from API using MailApiService
            val remoteFoldersResult = mailService.getMailFolders(null, accountId)

            if (remoteFoldersResult.isSuccess) {
                val remoteFolders = remoteFoldersResult.getOrThrow()
                Timber.d("Fetched ${remoteFolders.size} folders from API for account $accountId.")

                val folderEntities = remoteFolders.map {
                    val entity = it.toEntity(accountId)
                    entity.copy(
                        syncStatus = SyncStatus.SYNCED,
                        lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
                        lastSyncError = null
                    )
                }

                // Save FolderEntity list to folderDao (replace strategy)
                folderDao.deleteAllFoldersForAccount(accountId) // Clear old ones first
                folderDao.insertOrUpdateFolders(folderEntities)
                Timber.d("Updated folders in DB for account $accountId.")

                // Update sync metadata for account (e.g., last sync timestamp, status)
                accountDao.updateFolderListSyncSuccess(accountId, System.currentTimeMillis())
                Timber.d("Updated account $accountId folder list sync timestamp.")

                return Result.success()
            } else {
                val exception = remoteFoldersResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to fetch folders from API"
                Timber.e(
                    exception,
                    "Failed to fetch folders from API for account $accountId: $errorMessage"
                )
                accountDao.updateFolderListSyncError(accountId, errorMessage)

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to folder list sync failure.")
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
            Timber.e(e, "Error syncing folders for accountId: $accountId")
            accountDao.updateAccountSyncError(
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
}
