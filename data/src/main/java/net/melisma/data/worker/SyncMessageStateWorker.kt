package net.melisma.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.MessageDao
import timber.log.Timber

@HiltWorker
class SyncMessageStateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>, // For unified error handling
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val accountRepository: AccountRepository, // To fetch account details
    private val appDatabase: AppDatabase
) : CoroutineWorker(appContext, workerParams) {

    // TODO: Get these from a common constants place if they grow
    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_MESSAGE_ID = "MESSAGE_ID"
        const val KEY_OPERATION_TYPE = "OPERATION_TYPE"
        const val KEY_IS_READ = "IS_READ" // For MarkRead/Unread
        const val KEY_IS_STARRED = "IS_STARRED" // For Star/Unstar

        // Operation Types
        const val OP_MARK_READ = "MARK_READ"
        const val OP_STAR_MESSAGE = "STAR_MESSAGE"
    }

    private val TAG = "SyncMsgStateWorker"

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageId = inputData.getString(KEY_MESSAGE_ID)
        val operationType = inputData.getString(KEY_OPERATION_TYPE)

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank() || operationType.isNullOrBlank()) {
            Timber.tag(TAG)
                .e("Missing vital input data: accountId, messageId, or operationType. Failing.")
            return@withContext Result.failure()
        }

        Timber.tag(TAG)
            .i("Starting work for messageId: $messageId, accountId: $accountId, operation: $operationType")

        val account = try {
            accountRepository.getAccountByIdNonFlow(accountId) // Assuming a suspend fun for direct fetch
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch account details for accountId: $accountId")
            // updateSyncError is tricky here as we don't have messageId for it if account fetch fails early
            // Depending on how critical account fetch is, could retry or fail.
            return@withContext Result.retry()
        }

        if (account == null) {
            Timber.tag(TAG).e("Account not found for ID: $accountId. Cannot determine API service.")
            // This specific message ID won't be updated with this error, as it's an account-level issue.
            // The job will retry, and if the account remains missing, it will continue to retry.
            return@withContext Result.retry()
        }

        val providerType = account.providerType.uppercase()
        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null) {
            Timber.tag(TAG)
                .e("Could not find MailApiService for provider: $providerType (account $accountId)")
            updateSyncError(messageId, "API service not found for provider: $providerType")
            return@withContext Result.retry()
        }

        if (errorMapper == null) {
            Timber.tag(TAG)
                .e("Could not find ErrorMapperService for provider: $providerType (account $accountId)")
            updateSyncError(messageId, "Error mapper service not found for provider: $providerType")
            return@withContext Result.retry() // Or Result.failure() if this is unrecoverable
        }

        try {
            val apiResult: Result<Unit> = when (operationType) {
                OP_MARK_READ -> {
                    val isRead = inputData.getBoolean(KEY_IS_READ, false)
                    Timber.tag(TAG)
                        .d("Executing $OP_MARK_READ for $messageId to $isRead using $providerType API")
                    mailApiService.markMessageRead(messageId, isRead)
                }

                OP_STAR_MESSAGE -> {
                    val isStarred = inputData.getBoolean(KEY_IS_STARRED, false)
                    Timber.tag(TAG)
                        .d("Executing $OP_STAR_MESSAGE for $messageId to $isStarred using $providerType API")
                    mailApiService.starMessage(messageId, isStarred)
                }

                else -> {
                    Timber.tag(TAG).w("Unknown operation type: $operationType")
                    return@withContext Result.failure()
                }
            }
            return@withContext processApiResult(apiResult, messageId, errorMapper)
        } catch (e: Exception) { // Catch exceptions from the API calls directly if they aren't Result-wrapped
            Timber.tag(TAG).e(e, "Unhandled exception during API operation for $messageId")
            val errorDetails = errorMapper.mapExceptionToErrorDetails(e)
            updateSyncError(messageId, errorDetails.message ?: "Unknown error during sync")
            return@withContext Result.retry()
        }
    }

    private suspend fun processApiResult(
        apiResult: Result<Unit>,
        messageId: String,
        errorMapper: ErrorMapperService // Pass errorMapper
    ): Result {
        if (apiResult.isSuccess) {
            Timber.tag(TAG)
                .i("API call successful for $messageId. Clearing needsSync and lastSyncError.")
            appDatabase.withTransaction {
                messageDao.clearSyncState(messageId)
            }
            return Result.success()
        } else {
            val exception = apiResult.exceptionOrNull()
            val errorDetails = exception?.let { errorMapper.mapExceptionToErrorDetails(it) }
            val errorMessage = errorDetails?.message ?: exception?.message ?: "Unknown API error"

            Timber.tag(TAG).w("API call failed for $messageId: $errorMessage")
            updateSyncError(messageId, errorMessage)
            // TODO: Implement more sophisticated retry logic based on error type
            // For now, always retry for API failures.
            return Result.retry()
        }
    }

    private suspend fun updateSyncError(messageId: String, errorMessage: String) {
        try {
            Timber.tag(TAG).d("Updating sync error for $messageId: $errorMessage")
            appDatabase.withTransaction {
                messageDao.updateLastSyncError(
                    messageId,
                    errorMessage.take(500)
                ) // Limit error message length
            }
        } catch (dbException: Exception) {
            Timber.tag(TAG).e(dbException, "Failed to update lastSyncError in DB for $messageId")
        }
    }
} 