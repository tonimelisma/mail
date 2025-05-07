package net.melisma.data.repository

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
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.core_common.errors.ErrorMapperService
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default implementation of the [MessageRepository] interface.
 * Currently only handles Microsoft accounts using Microsoft Graph API.
 * Future: Will be extended to support multiple account types (Google, etc.).
 */
@Singleton
class DefaultMessageRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper, // Will be replaced with a Map<String, ApiHelper> in future
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapperService
) : MessageRepository {

    private val TAG = "DefaultMessageRepo"

    private val _messageDataState = MutableStateFlow<MessageDataState>(MessageDataState.Initial)
    override val messageDataState: StateFlow<MessageDataState> = _messageDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    private var messageFetchJob: Job? = null

    // Define fields needed for message list API call
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25 // Or your preferred page size

    override suspend fun setTargetFolder(account: Account?, folder: MailFolder?) {
        // Use the injected scope's context
        withContext(externalScope.coroutineContext) {
            if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id) {
                Log.d(TAG, "setTargetFolder called with the same target. Skipping fetch.")
                return@withContext
            }

            Log.i(TAG, "Setting target folder: Account=${account?.id}, Folder=${folder?.id}")
            cancelAndClearJob() // Cancel any previous job

            currentTargetAccount = account
            currentTargetFolder = folder

            if (account == null || folder == null) {
                _messageDataState.value = MessageDataState.Initial
            } else {
                // Handle different provider types
                when (account.providerType) {
                    "MS" -> {
                        _messageDataState.value = MessageDataState.Loading
                        launchMicrosoftMessageFetchJob(
                            account,
                            folder,
                            isRefresh = false,
                            activity = null
                        )
                    }

                    else -> {
                        Log.w(TAG, "Unsupported account provider type: ${account.providerType}")
                        _messageDataState.value =
                            MessageDataState.Error("Unsupported account provider type")
                    }
                }
            }
        }
    }

    override suspend fun refreshMessages(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder

        if (account == null || folder == null) {
            Log.w(TAG, "refreshMessages called but no target folder is set.")
            return
        }

        // Handle different provider types
        when (account.providerType) {
            "MS" -> refreshMicrosoftMessages(account, folder, activity)
            else -> {
                Log.w(
                    TAG,
                    "refreshMessages called for unsupported account type: ${account.providerType}"
                )
            }
        }
    }

    /**
     * Microsoft-specific refresh implementation.
     */
    private suspend fun refreshMicrosoftMessages(
        account: Account,
        folder: MailFolder,
        activity: Activity?
    ) {
        if (_messageDataState.value is MessageDataState.Loading) {
            Log.d(TAG, "Refresh skipped: Already loading messages for ${folder.id}.")
            return
        }

        Log.d(TAG, "Refreshing messages for folder: ${folder.id}, account: ${account.id}")
        // Use the injected scope's context
        withContext(externalScope.coroutineContext) {
            // Set loading state immediately before launching the job
            _messageDataState.value = MessageDataState.Loading
            launchMicrosoftMessageFetchJob(account, folder, isRefresh = true, activity = activity)
        }
    }

    /**
     * Microsoft-specific message fetch job.
     */
    private fun launchMicrosoftMessageFetchJob(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean,
        activity: Activity?
    ) {
        // Cancel previous job before starting a new one
        cancelAndClearJob()

        Log.d(
            TAG,
            "Launching message fetch job for ${folder.id}/${account.username}. Refresh: $isRefresh"
        )
        messageFetchJob = externalScope.launch(ioDispatcher) { // Launch in IO dispatcher
            try {
                // 1. Get Token
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive() // Check for cancellation after suspend call

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(TAG, "Token acquired for ${account.username}, fetching messages...")

                    // 2. Fetch Messages
                    val messagesResult = graphApiHelper.getMessagesForFolder(
                        accessToken, folder.id, messageListSelectFields, messageListPageSize
                    )
                    ensureActive() // Check for cancellation

                    // 3. Update State based on result
                    val newState = if (messagesResult.isSuccess) {
                        val messages = messagesResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${messages.size} messages for ${folder.id}"
                        )
                        MessageDataState.Success(messages)
                    } else {
                        val errorMsg =
                            errorMapper.mapNetworkOrApiException(messagesResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch messages for ${folder.id}: $errorMsg",
                            messagesResult.exceptionOrNull()
                        )
                        MessageDataState.Error(errorMsg)
                    }

                    // Check if the target is still the same before updating the state flow
                    if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = newState
                    } else {
                        Log.w(
                            TAG,
                            "Message fetch completed but target changed. Discarding result for ${folder.id}"
                        )
                    }

                } else { // Token acquisition failure
                    val errorMsg =
                        errorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for messages: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive() // Check for cancellation

                    // Check if the target is still the same before updating the state flow
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
                // Reset state only if cancelled while loading and target is still the same
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value is MessageDataState.Loading
                ) {
                    _messageDataState.value =
                        MessageDataState.Initial // Reset to initial on cancellation
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Exception during message fetch for ${folder.id}/${account.username}", e)
                ensureActive() // Check for cancellation

                val errorMsg = errorMapper.mapAuthExceptionToUserMessage(e)
                // Check if the target is still the same before updating the state flow
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but target changed. Discarding error for ${folder.id}"
                    )
                }
            } finally {
                // Clear the job reference only if this coroutine's job is the one stored
                if (messageFetchJob == coroutineContext[Job]) {
                    messageFetchJob = null
                }
            }
        }

        // Optional: Handle unhandled job failures
        messageFetchJob?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(TAG, "Unhandled error completing message fetch job for ${folder.id}", cause)
                // Check if the target is still the same before updating the state flow
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value !is MessageDataState.Error
                ) {
                    val errorMsg = errorMapper.mapAuthExceptionToUserMessage(cause)
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                }
            }
        }
    }

    /** Cancels the active fetch job, if any, and clears the reference. */
    private fun cancelAndClearJob() {
        val jobToCancel = messageFetchJob
        messageFetchJob = null // Clear the reference immediately
        jobToCancel?.let {
            if (it.isActive) {
                // Provide a more specific cancellation message
                it.cancel(CancellationException("New message target set or refresh triggered."))
                Log.d(TAG, "Cancelled previous message fetch job.")
            }
        }
    }
}