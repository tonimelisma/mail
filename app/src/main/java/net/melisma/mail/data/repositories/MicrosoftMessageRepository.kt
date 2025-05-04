package net.melisma.mail.data.repositories

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.melisma.mail.Account
import net.melisma.mail.GraphApiHelper
import net.melisma.mail.MailFolder
import net.melisma.mail.data.datasources.TokenProvider
import net.melisma.mail.di.ApplicationScope
import net.melisma.mail.model.MessageDataState
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implementation of MessageRepository using Microsoft Graph API.
 * Fetches messages for specific folders within Microsoft accounts.
 */
@Singleton
class MicrosoftMessageRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope // Use application scope for jobs
) : MessageRepository {

    private val TAG = "MsMessageRepository"

    // Holds the state for the currently targeted folder's messages.
    private val _messageDataState = MutableStateFlow<MessageDataState>(MessageDataState.Initial)
    override val messageDataState: StateFlow<MessageDataState> = _messageDataState.asStateFlow()

    // Holds the currently targeted account and folder.
    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null

    // Manages the active message fetch job.
    private var messageFetchJob: Job? = null

    // Configuration (could be injected or constants)
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25 // TODO: Implement pagination

    /**
     * Sets the target folder and triggers fetching messages.
     */
    override suspend fun setTargetFolder(account: Account?, folder: MailFolder?) {
        // Use the external scope to ensure thread safety when modifying target state
        withContext(externalScope.coroutineContext) {
            if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id) {
                Log.d(TAG, "setTargetFolder called with the same target. Skipping fetch.")
                return@withContext
            }

            Log.i(TAG, "Setting target folder: Account=${account?.id}, Folder=${folder?.id}")
            cancelAndClearJob() // Cancel previous job

            currentTargetAccount = account
            currentTargetFolder = folder

            if (account == null || folder == null) {
                _messageDataState.value = MessageDataState.Initial
            } else {
                // Launch fetch job within the externalScope
                _messageDataState.value = MessageDataState.Loading
                launchMessageFetchJob(account, folder, isRefresh = false, activity = null)
            }
        }
    }

    /**
     * Refreshes messages for the currently set target folder.
     */
    override suspend fun refreshMessages(activity: Activity?) {
        // Read target state within the scope for consistency
        val account = currentTargetAccount
        val folder = currentTargetFolder

        if (account == null || folder == null) {
            Log.w(TAG, "refreshMessages called but no target folder is set.")
            return
        }

        // Check loading state within the scope
        if (_messageDataState.value is MessageDataState.Loading) {
            Log.d(TAG, "Refresh skipped: Already loading messages for ${folder.id}.")
            return
        }

        Log.d(TAG, "Refreshing messages for folder: ${folder.id}, account: ${account.id}")
        // Set state and launch job within the external scope
        withContext(externalScope.coroutineContext) {
            _messageDataState.value = MessageDataState.Loading
            launchMessageFetchJob(account, folder, isRefresh = true, activity = activity)
        }
    }

    /**
     * Launches or replaces the message fetch coroutine job.
     * IMPORTANT: This function should be called from within a coroutine scope (like externalScope).
     */
    private fun launchMessageFetchJob(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean,
        activity: Activity?
    ) {
        if (account.providerType != "MS") {
            Log.e(TAG, "Cannot fetch messages for non-MS account type: ${account.providerType}")
            _messageDataState.value = MessageDataState.Error("Unsupported account type.")
            return
        }

        cancelAndClearJob() // Cancel existing job

        Log.d(
            TAG,
            "Launching message fetch job for ${folder.id}/${account.username}. Refresh: $isRefresh"
        )
        // Launch the job within the externalScope provided via injection
        messageFetchJob = externalScope.launch(ioDispatcher) { // Use injected dispatcher
            try {
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive()

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(TAG, "Token acquired for ${account.username}, fetching messages...")

                    val messagesResult = graphApiHelper.getMessagesForFolder(
                        accessToken, folder.id, messageListSelectFields, messageListPageSize
                    )
                    ensureActive()

                    val newState = if (messagesResult.isSuccess) {
                        val messages = messagesResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${messages.size} messages for ${folder.id}"
                        )
                        MessageDataState.Success(messages)
                    } else {
                        val errorMsg =
                            mapGraphExceptionToUserMessage(messagesResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch messages for ${folder.id}: $errorMsg",
                            messagesResult.exceptionOrNull()
                        )
                        MessageDataState.Error(errorMsg)
                    }
                    // Update state only if the target hasn't changed concurrently
                    if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = newState
                    } else {
                        Log.w(
                            TAG,
                            "Message fetch completed but target changed. Discarding result for ${folder.id}"
                        )
                    }

                } else { // Token acquisition failed
                    val errorMsg = mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for messages: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive()
                    // Update state only if the target hasn't changed concurrently
                    if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = MessageDataState.Error(errorMsg)
                    } else {
                        Log.w(
                            TAG,
                            "Token error received but target changed. Discarding error for ${folder.id}"
                        )
                    }
                }

            } catch (e: CancellationException) {
                Log.w(TAG, "Message fetch job for ${folder.id}/${account.username} cancelled.", e)
                // Reset state only if the cancellation pertains to the *current* target
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value is MessageDataState.Loading
                ) {
                    _messageDataState.value = MessageDataState.Initial
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during message fetch for ${folder.id}/${account.username}", e)
                ensureActive()
                val errorMsg =
                    if (e is MsalException) mapAuthExceptionToUserMessage(e) else mapGraphExceptionToUserMessage(
                        e
                    )
                // Update state only if the target hasn't changed concurrently
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but target changed. Discarding error for ${folder.id}"
                    )
                }
            } finally {
                // Clear job reference only if this specific job instance finished.
                if (messageFetchJob == coroutineContext[Job]) {
                    messageFetchJob = null
                    Log.d(TAG, "Cleared completed/failed message job reference for ${folder.id}")
                }
            }
        }

        // Optional: Handle job completion for logging unhandled errors
        messageFetchJob?.invokeOnCompletion { cause ->
            // Only log if the cause is not cancellation and the scope is still active
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(TAG, "Unhandled error completing message fetch job for ${folder.id}", cause)
                // *** REMOVED state update from here - should be handled in catch block ***
                // Check target again before potentially logging an error state was missed
                // if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                //     _messageDataState.value !is MessageDataState.Error) {
                //     val errorMsg = if (cause is MsalException) mapAuthExceptionToUserMessage(cause) else mapGraphExceptionToUserMessage(cause)
                //     _messageDataState.value = MessageDataState.Error(errorMsg)
                //  }
            }
        }
    }

    /** Cancels the current message fetch job and clears the reference. */
    private fun cancelAndClearJob() {
        val jobToCancel = messageFetchJob
        messageFetchJob = null
        jobToCancel?.let {
            if (it.isActive) {
                it.cancel(CancellationException("New message target set or refresh triggered."))
                Log.d(TAG, "Cancelled previous message fetch job.")
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
                ?: "An unknown error occurred while fetching messages"
        }
    }

    /** Maps Authentication (MSAL) related exceptions to user-friendly messages. */
    private fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }
        if (exception !is MsalException) {
            return mapGraphExceptionToUserMessage(exception) // Fallback
        }
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        val code = exception.errorCode ?: "UNKNOWN"
        return when (exception) {
            is MsalUserCancelException -> "Authentication cancelled."
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalClientException -> when (exception.errorCode) {
                MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                // Add other specific MsalClientException codes here if needed
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication client error ($code)"
            }

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error ($code)"

            else -> exception.message?.takeIf { it.isNotBlank() } ?: "Authentication failed ($code)"
        }
    }
}
