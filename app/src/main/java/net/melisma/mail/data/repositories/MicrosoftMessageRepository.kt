package net.melisma.mail.data.repositories

import android.app.Activity
import android.util.Log
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
import net.melisma.mail.data.errors.ErrorMapper
import net.melisma.mail.di.ApplicationScope
import net.melisma.mail.model.MessageDataState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implementation of [MessageRepository] using Microsoft Graph API.
 * Fetches messages for specific folders within Microsoft accounts. Manages the
 * state of the message list for the currently selected folder, handling loading,
 * success, and error states via [MessageDataState].
 */
@Singleton
class MicrosoftMessageRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope // Use application scope for jobs
) : MessageRepository {

    private val TAG = "MsMessageRepository"

    // Holds the state for the currently targeted folder's messages. Starts as Initial.
    private val _messageDataState = MutableStateFlow<MessageDataState>(MessageDataState.Initial)

    /**
     * A [StateFlow] emitting the current fetch state ([MessageDataState]) for messages
     * in the currently targeted folder/account. UI layers collect this to display
     * loading indicators, message lists, or error messages.
     */
    override val messageDataState: StateFlow<MessageDataState> = _messageDataState.asStateFlow()

    // References to the account and folder currently being targeted for message display.
    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null

    // Manages the active coroutine job fetching messages to allow cancellation.
    private var messageFetchJob: Job? = null

    // Configuration for Graph API calls
    // Defines which fields to retrieve for the message list to minimize data transfer.
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )

    // Defines how many messages to fetch per request.
    private val messageListPageSize = 25 // TODO: Implement pagination based on this size.

    /**
     * Sets the target account and folder for which messages should be fetched and observed.
     * If the target changes, any ongoing fetch is cancelled, and a new fetch is initiated.
     * If the target is cleared (nulls passed), the state resets to [MessageDataState.Initial].
     *
     * @param account The target [Account] or null to clear the target.
     * @param folder The target [MailFolder] or null to clear the target.
     */
    override suspend fun setTargetFolder(account: Account?, folder: MailFolder?) {
        // Use the repository's scope to ensure thread-safe access to target state.
        withContext(externalScope.coroutineContext) {
            // Avoid fetching if the target hasn't actually changed.
            if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id) {
                Log.d(TAG, "setTargetFolder called with the same target. Skipping fetch.")
                return@withContext
            }

            Log.i(TAG, "Setting target folder: Account=${account?.id}, Folder=${folder?.id}")
            cancelAndClearJob() // Cancel any previous fetch job

            // Update the current target references.
            currentTargetAccount = account
            currentTargetFolder = folder

            if (account == null || folder == null) {
                // If target is cleared, reset state to Initial.
                _messageDataState.value = MessageDataState.Initial
            } else {
                // If a valid target is set, initiate loading.
                _messageDataState.value = MessageDataState.Loading
                // Launch the fetch job within the externalScope to handle its lifecycle.
                launchMessageFetchJob(account, folder, isRefresh = false, activity = null)
            }
        }
    }

    /**
     * Triggers a refresh of messages for the currently set target folder.
     * If no folder is targeted or a fetch is already in progress, the refresh is skipped.
     *
     * @param activity The optional [Activity] context, potentially needed for interactive token acquisition.
     */
    override suspend fun refreshMessages(activity: Activity?) {
        // Read target state safely within the scope.
        val account = currentTargetAccount
        val folder = currentTargetFolder

        // Cannot refresh if no target is set.
        if (account == null || folder == null) {
            Log.w(TAG, "refreshMessages called but no target folder is set.")
            _messageDataState.value =
                MessageDataState.Error("No folder selected to refresh.") // Provide feedback
            return
        }

        // Avoid concurrent refreshes if already loading.
        if (_messageDataState.value is MessageDataState.Loading) {
            Log.d(TAG, "Refresh skipped: Already loading messages for ${folder.id}.")
            return
        }

        Log.d(TAG, "Refreshing messages for folder: ${folder.id}, account: ${account.id}")
        // Set state to Loading and launch the job within the external scope.
        withContext(externalScope.coroutineContext) {
            _messageDataState.value = MessageDataState.Loading
            launchMessageFetchJob(account, folder, isRefresh = true, activity = activity)
        }
    }

    /**
     * Internal function to launch or replace the message fetch coroutine job.
     * Handles job cancellation, state updates, token acquisition, API calls, and error mapping.
     *
     * This function MUST be called from within the [externalScope] or a context derived from it.
     *
     * @param account The account whose folder messages are being fetched.
     * @param folder The specific folder to fetch messages from.
     * @param isRefresh Indicates if this is a user-initiated refresh.
     * @param activity Optional [Activity] for interactive auth flows.
     */
    private fun launchMessageFetchJob(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean,
        activity: Activity?
    ) {
        // This repository only handles Microsoft accounts.
        if (account.providerType != "MS") {
            Log.e(TAG, "Cannot fetch messages for non-MS account type: ${account.providerType}")
            _messageDataState.value = MessageDataState.Error("Unsupported account type.")
            return
        }

        // Cancel any existing fetch job before starting a new one.
        cancelAndClearJob()

        Log.d(
            TAG,
            "Launching message fetch job for ${folder.id}/${account.username}. Refresh: $isRefresh"
        )
        // Launch the background job using the injected IO dispatcher and external scope.
        messageFetchJob = externalScope.launch(ioDispatcher) {
            try {
                // Step 1: Acquire Access Token
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive() // Check for cancellation after suspend function

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(TAG, "Token acquired for ${account.username}, fetching messages...")

                    // Step 2: Fetch Messages from Graph API
                    val messagesResult = graphApiHelper.getMessagesForFolder(
                        accessToken, folder.id, messageListSelectFields, messageListPageSize
                    )
                    ensureActive() // Check for cancellation

                    // Step 3: Process Result and Update State
                    val newState = if (messagesResult.isSuccess) {
                        val messages = messagesResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${messages.size} messages for ${folder.id}"
                        )
                        MessageDataState.Success(messages)
                    } else {
                        // Use centralized mapper for Graph errors
                        val errorMsg =
                            ErrorMapper.mapGraphExceptionToUserMessage(messagesResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch messages for ${folder.id}: $errorMsg",
                            messagesResult.exceptionOrNull()
                        )
                        MessageDataState.Error(errorMsg)
                    }
                    // Update state only if the target hasn't changed while fetching.
                    if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = newState
                    } else {
                        Log.w(
                            TAG,
                            "Message fetch completed but target changed. Discarding result for ${folder.id}"
                        )
                    }

                } else { // Token acquisition failed
                    // Use centralized mapper for Auth errors
                    val errorMsg =
                        ErrorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for messages: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive() // Check for cancellation
                    // Update state only if the target hasn't changed.
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
                // Job was cancelled.
                Log.w(TAG, "Message fetch job for ${folder.id}/${account.username} cancelled.", e)
                // If cancelled while loading, reset state to Initial only if the target still matches.
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value is MessageDataState.Loading
                ) {
                    _messageDataState.value = MessageDataState.Initial // Or Error("Cancelled")
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                // Catch any other unexpected exceptions.
                Log.e(TAG, "Exception during message fetch for ${folder.id}/${account.username}", e)
                ensureActive() // Check for cancellation
                // Map the exception using the centralized mapper.
                val errorMsg =
                    ErrorMapper.mapAuthExceptionToUserMessage(e) // mapAuth handles non-Msal fallback
                // Update state only if the target hasn't changed.
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but target changed. Discarding error for ${folder.id}"
                    )
                }
            } finally {
                // Clean up job reference when the coroutine completes.
                // Check if the completed job is the *current* job reference to avoid race conditions.
                if (messageFetchJob == coroutineContext[Job]) {
                    messageFetchJob = null
                    Log.d(TAG, "Cleared completed/failed message job reference for ${folder.id}")
                }
            }
        }

        // Optional: Completion handler for logging, though errors handled in catch.
        messageFetchJob?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(TAG, "Unhandled error completing message fetch job for ${folder.id}", cause)
                // Defensive state update if error missed
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value !is MessageDataState.Error
                ) {
                    val errorMsg = ErrorMapper.mapAuthExceptionToUserMessage(cause)
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                }
            }
        }
    }

    /**
     * Cancels the current message fetch job, if active, and clears the reference.
     */
    private fun cancelAndClearJob() {
        val jobToCancel = messageFetchJob
        messageFetchJob = null // Clear reference immediately
        jobToCancel?.let {
            if (it.isActive) {
                it.cancel(CancellationException("New message target set or refresh triggered."))
                Log.d(TAG, "Cancelled previous message fetch job.")
            }
        }
    }
}