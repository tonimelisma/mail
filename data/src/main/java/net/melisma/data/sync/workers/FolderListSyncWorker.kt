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

    override suspend fun doWork(): Result {
        val accountId = workerParams.inputData.getString(KEY_ACCOUNT_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Account ID is missing"))

        Timber.d("doWork: Starting folder list sync for accountId: $accountId")

        val account = accountRepository.getAccountById(accountId).firstOrNull()
        if (account == null) {
            Timber.e("doWork: Account not found for ID: $accountId")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Account not found"))
        }

        val apiService = mailApiServiceSelector.getServiceByAccountId(accountId)
        if (apiService == null) {
            Timber.e("doWork: No API service found for account ID: $accountId")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unsupported provider type or account issue"))
        }

        try {
            val remoteFoldersResult = apiService.getMailFolders(null, accountId)

            if (remoteFoldersResult.isSuccess) {
                val remoteFolders = remoteFoldersResult.getOrThrow()
                database.withTransaction {
                    remoteFolders.forEach { mailFolder: MailFolder ->
                        val entity = mailFolder.toEntity(accountId, UUID.randomUUID().toString())
                        database.folderDao().insertOrUpdateFolders(listOf(entity))
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
    }
}
