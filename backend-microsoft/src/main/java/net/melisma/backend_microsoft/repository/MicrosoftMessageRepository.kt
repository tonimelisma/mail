package net.melisma.backend_microsoft.repository

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
import net.melisma.backend_microsoft.errors.ErrorMapper
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
 * Microsoft implementation of the [MessageRepository] interface.
 * Fetches messages for a specific folder within a Microsoft account using the Graph API.
 * Manages the state ([MessageDataState]) for the currently targeted folder.
 */
@Singleton
class MicrosoftMessageRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper,
    // Use the qualifier imported from core-data
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    // Use the qualifier imported from core-data
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapper
) : MessageRepository {

    private val TAG = "MsMessageRepository"

    private val _messageDataState = MutableStateFlow<MessageDataState>(MessageDataState.Initial)
    override val messageDataState: StateFlow<MessageDataState> = _messageDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    private var messageFetchJob: Job? = null

    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25

    override suspend fun setTargetFolder(account: Account?, folder: MailFolder?) {
        withContext(externalScope.coroutineContext) {
            if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id) {
                Log.d(TAG, "setTargetFolder called with the same target. Skipping fetch.")
                return@withContext
            }

            Log.i(TAG, "Setting target folder: Account=${account?.id}, Folder=${folder?.id}")
            cancelAndClearJob()

            currentTargetAccount = account
            currentTargetFolder = folder

            if (account == null || folder == null) {
                _messageDataState.value = MessageDataState.Initial
            } else {
                _messageDataState.value = MessageDataState.Loading
                launchMessageFetchJob(account, folder, isRefresh = false, activity = null)
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

        if (_messageDataState.value is MessageDataState.Loading) {
            Log.d(TAG, "Refresh skipped: Already loading messages for ${folder.id}.")
            return
        }

        Log.d(TAG, "Refreshing messages for folder: ${folder.id}, account: ${account.id}")
        withContext(externalScope.coroutineContext) {
            _messageDataState.value = MessageDataState.Loading
            launchMessageFetchJob(account, folder, isRefresh = true, activity = activity)
        }
    }

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
        cancelAndClearJob()

        Log.d(
            TAG,
            "Launching message fetch job for ${folder.id}/${account.username}. Refresh: $isRefresh"
        )
        messageFetchJob = externalScope.launch(ioDispatcher) {
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
                            errorMapper.mapGraphExceptionToUserMessage(messagesResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch messages for ${folder.id}: $errorMsg",
                            messagesResult.exceptionOrNull()
                        )
                        MessageDataState.Error(errorMsg)
                    }
                    if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = newState
                    } else {
                        Log.w(
                            TAG,
                            "Message fetch completed but target changed. Discarding result for ${folder.id}"
                        )
                    }

                } else { // Token failure
                    val errorMsg =
                        errorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for messages: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive()
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
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value is MessageDataState.Loading
                ) {
                    _messageDataState.value = MessageDataState.Initial
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during message fetch for ${folder.id}/${account.username}", e)
                ensureActive()
                val errorMsg = errorMapper.mapAuthExceptionToUserMessage(e)
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but target changed. Discarding error for ${folder.id}"
                    )
                }
            } finally {
                if (messageFetchJob == coroutineContext[Job]) {
                    messageFetchJob = null
                }
            }
        }

        messageFetchJob?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(TAG, "Unhandled error completing message fetch job for ${folder.id}", cause)
                if (account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value !is MessageDataState.Error
                ) {
                    val errorMsg = errorMapper.mapAuthExceptionToUserMessage(cause)
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                }
            }
        }
    }

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
}