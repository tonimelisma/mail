package net.melisma.mail.data.repositories

// Import MSAL exceptions used in error mapping
import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.melisma.mail.Account
import net.melisma.mail.GraphApiHelper
import net.melisma.mail.data.datasources.TokenProvider
import net.melisma.mail.di.ApplicationScope
import net.melisma.mail.model.FolderFetchState
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implementation of FolderRepository using Microsoft Graph API.
 * Fetches mail folders for Microsoft accounts.
 */
@Singleton
class MicrosoftFolderRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope
) : FolderRepository {

    private val TAG = "MsFolderRepository"

    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())
    private val folderFetchJobs = ConcurrentHashMap<String, Job>()
    private val observedAccounts = ConcurrentHashMap<String, Account>()

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(externalScope.coroutineContext + ioDispatcher) {
            val newMicrosoftAccounts = accounts.filter { it.providerType == "MS" }
            val newAccountIds = newMicrosoftAccounts.map { it.id }.toSet()
            val currentAccountIds = observedAccounts.keys.toSet()

            // 1. Handle removed accounts
            val removedAccountIds = currentAccountIds - newAccountIds
            removedAccountIds.forEach { accountId ->
                cancelAndRemoveJob(accountId)
                observedAccounts.remove(accountId)
                _folderStates.update { it - accountId }
            }

            // 2. Handle added accounts
            val addedAccounts = newMicrosoftAccounts.filter { it.id !in currentAccountIds }
            addedAccounts.forEach { account ->
                observedAccounts[account.id] = account
                val currentState = _folderStates.value[account.id]
                if (currentState == null || currentState is FolderFetchState.Error) {
                    launchFolderFetchJob(account, isInitialLoad = true)
                } else {
                    Log.d(
                        TAG,
                        "Skipping initial fetch for ${account.username}, state already exists: $currentState"
                    )
                }
            }
            Log.d(TAG, "Managed observed accounts. Now tracking: ${observedAccounts.keys}")
        }
    }

    override suspend fun refreshAllFolders(activity: Activity?) {
        val accountsToRefresh = observedAccounts.values.toList()
        Log.d(TAG, "Refreshing folders for ${accountsToRefresh.size} observed accounts...")
        accountsToRefresh.forEach { account ->
            launchFolderFetchJob(
                account,
                isInitialLoad = false,
                activity = activity,
                forceRefresh = true
            )
        }
    }

    private fun launchFolderFetchJob(
        account: Account,
        isInitialLoad: Boolean,
        activity: Activity? = null,
        forceRefresh: Boolean = false
    ) {
        val accountId = account.id

        if (!forceRefresh && folderFetchJobs[accountId]?.isActive == true) {
            Log.d(TAG, "Folder fetch job already active for account $accountId. Skipping.")
            return
        }

        cancelAndRemoveJob(accountId)

        Log.d(
            TAG,
            "Launching folder fetch job for account ${account.username} (ID: $accountId). Force: $forceRefresh, Initial: $isInitialLoad"
        )

        _folderStates.update { currentMap ->
            val currentState = currentMap[accountId]
            val newState =
                if (forceRefresh && currentState is FolderFetchState.Success) currentState else FolderFetchState.Loading
            currentMap + (accountId to newState)
        }

        val job = externalScope.launch(ioDispatcher) {
            try {
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive()

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(TAG, "Token acquired for ${account.username}, fetching folders...")
                    val foldersResult = graphApiHelper.getMailFolders(accessToken)
                    ensureActive()

                    val newState = if (foldersResult.isSuccess) {
                        val folders = foldersResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${folders.size} folders for ${account.username}"
                        )
                        FolderFetchState.Success(folders)
                    } else {
                        val errorMsg =
                            mapGraphExceptionToUserMessage(foldersResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch folders for ${account.username}: $errorMsg",
                            foldersResult.exceptionOrNull()
                        )
                        FolderFetchState.Error(errorMsg)
                    }
                    _folderStates.update { it + (accountId to newState) }

                } else { // Token acquisition failed
                    // Use the specific mapping function for auth errors
                    val errorMsg = mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for ${account.username}: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive()
                    _folderStates.update {
                        it + (accountId to FolderFetchState.Error(errorMsg))
                    }
                }

            } catch (e: CancellationException) {
                Log.w(TAG, "Folder fetch job for ${account.username} cancelled.", e)
                if (_folderStates.value[accountId] is FolderFetchState.Loading) {
                    _folderStates.update { it - accountId }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during folder fetch for ${account.username}", e)
                ensureActive()
                // Determine if it's an auth error or graph error
                val errorMsg =
                    if (e is MsalException) mapAuthExceptionToUserMessage(e) else mapGraphExceptionToUserMessage(
                        e
                    )
                _folderStates.update {
                    it + (accountId to FolderFetchState.Error(errorMsg))
                }
            } finally {
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                    Log.d(TAG, "Removed completed/failed job reference for account $accountId")
                }
            }
        }
        folderFetchJobs[accountId] = job

        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                Log.e(TAG, "Unhandled error in folder fetch job for $accountId", cause)
                if (externalScope.isActive && _folderStates.value[accountId] !is FolderFetchState.Error) {
                    val errorMsg =
                        if (cause is MsalException) mapAuthExceptionToUserMessage(cause) else mapGraphExceptionToUserMessage(
                            cause
                        )
                    _folderStates.update {
                        it + (accountId to FolderFetchState.Error(errorMsg))
                    }
                }
            }
        }
    }

    /** Safely cancels and removes the job reference for an account ID. */
    private fun cancelAndRemoveJob(accountId: String) {
        folderFetchJobs.remove(accountId)?.apply {
            if (isActive) {
                cancel(CancellationException("Job cancelled for account $accountId"))
                Log.d(TAG, "Cancelled active job for account $accountId")
            }
        }
    }


    // --- Error Mapping Helpers ---

    /** Maps Graph API related exceptions to user-friendly messages. */
    private fun mapGraphExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping graph exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}")
        return when (exception) {
            is UnknownHostException -> "No internet connection"
            is IOException -> "Network error occurred"
            else -> exception?.message?.takeIf { it.isNotBlank() }
                ?: "An unknown error occurred while fetching folders"
        }
    }

    /** Maps Authentication (MSAL) related exceptions to user-friendly messages. */
    private fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        // Handle standard CancellationException first if passed directly
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }
        if (exception !is MsalException) {
            return mapGraphExceptionToUserMessage(exception) // Fallback if not MSAL specific
        }
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        val code = exception.errorCode ?: "UNKNOWN"
        return when (exception) {
            // Check specific types first
            is MsalUserCancelException -> "Authentication cancelled."
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalClientException -> when (exception.errorCode) {
                MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                // Add other specific MsalClientException codes here based on documentation
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication client error ($code)"
            }

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error ($code)"

            else -> exception.message?.takeIf { it.isNotBlank() } ?: "Authentication failed ($code)"
        }
    }
}
