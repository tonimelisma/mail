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
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultMessageRepository @Inject constructor(
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : MessageRepository {

    private val TAG = "DefaultMessageRepo" // Logging TAG

    private val _messageDataState = MutableStateFlow<MessageDataState>(MessageDataState.Initial)
    override val messageDataState: StateFlow<MessageDataState> = _messageDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    private var messageFetchJob: Job? = null

    private val messageListSelectFields = listOf(
        "id", "conversationId", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25

    init {
        Log.d(
            TAG, "Initializing DefaultMessageRepository. Injected maps:" +
                    " mailApiServices keys: ${mailApiServices.keys}," +
                    " errorMappers keys: ${errorMappers.keys}"
        )
    }

    override suspend fun setTargetFolder(account: Account?, folder: MailFolder?) {
        Log.d(
            TAG,
            "setTargetFolder called. Account: ${account?.username}, Folder: ${folder?.displayName}. Current job active: ${messageFetchJob?.isActive}"
        )
        withContext(externalScope.coroutineContext) { // Using externalScope.coroutineContext as per original for this block
            if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id) {
                Log.d(
                    TAG,
                    "setTargetFolder: Same target (Account ID: ${account?.id}, Folder ID: ${folder?.id}). Skipping fetch."
                )
                return@withContext
            }

            Log.i(
                TAG,
                "setTargetFolder: New target. Account=${account?.username}, Folder=${folder?.displayName} (ID: ${folder?.id})"
            )
            cancelAndClearJob("setTargetFolder new target")

            currentTargetAccount = account
            currentTargetFolder = folder

            if (account == null || folder == null) {
                Log.d(TAG, "setTargetFolder: Account or Folder is null, setting state to Initial.")
                _messageDataState.value = MessageDataState.Initial
            } else {
                val providerType = account.providerType.uppercase()
                Log.d(TAG, "setTargetFolder: Checking support for providerType '$providerType'.")
                if (mailApiServices.containsKey(providerType) &&
                    errorMappers.containsKey(providerType)
                ) {
                    Log.d(
                        TAG,
                        "setTargetFolder: Provider '$providerType' supported. Setting state to Loading and launching fetch job."
                    )
                    _messageDataState.value = MessageDataState.Loading
                    launchMessageFetchJob(account, folder, isRefresh = false, activity = null)
                } else {
                    Log.w(
                        TAG,
                        "setTargetFolder: Provider '$providerType' NOT supported or missing services. " +
                                "mailApiServices has '$providerType'? ${
                                    mailApiServices.containsKey(
                                        providerType
                                    )
                                }. " +
                                "errorMappers has '$providerType'? ${
                                    errorMappers.containsKey(
                                        providerType
                                    )
                                }." +
                                "Available errorMapper keys: ${errorMappers.keys}"
                    )
                    _messageDataState.value =
                        MessageDataState.Error("Unsupported account provider type: $providerType, or missing services.")
                }
            }
        }
    }

    override suspend fun refreshMessages(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder
        Log.d(
            TAG,
            "refreshMessages called. Account: ${account?.username}, Folder: ${folder?.displayName}. Current state: ${_messageDataState.value}"
        )

        if (account == null || folder == null) {
            Log.w(TAG, "refreshMessages: No target folder or account set. Skipping.")
            return
        }

        val providerType = account.providerType.uppercase()
        Log.d(TAG, "refreshMessages: Checking support for providerType '$providerType'.")
        if (mailApiServices.containsKey(providerType) &&
            errorMappers.containsKey(providerType)
        ) {
            Log.d(
                TAG,
                "refreshMessages: Provider '$providerType' supported. Proceeding with refresh."
            )
            refreshMessagesForProvider(account, folder, activity)
        } else {
            Log.w(
                TAG,
                "refreshMessages: Provider '$providerType' NOT supported or missing services for refresh. " +
                        "mailApiServices has '$providerType'? ${
                            mailApiServices.containsKey(
                                providerType
                            )
                        }. " +
                        "errorMappers has '$providerType'? ${errorMappers.containsKey(providerType)}."
            )
            _messageDataState.value =
                MessageDataState.Error("Cannot refresh: Unsupported account provider type: $providerType, or missing services.")
        }
    }

    private suspend fun refreshMessagesForProvider(
        account: Account,
        folder: MailFolder,
        activity: Activity?
    ) {
        if (_messageDataState.value is MessageDataState.Loading) {
            Log.d(
                TAG,
                "refreshMessagesForProvider: Skipped. Already loading messages for folder: ${folder.displayName}."
            )
            return
        }
        Log.i(
            TAG,
            "refreshMessagesForProvider: Refreshing messages for folder: ${folder.displayName}, account: ${account.username}"
        )
        withContext(externalScope.coroutineContext) { // Using externalScope.coroutineContext
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
        Log.d(
            TAG,
            "launchMessageFetchJob: Preparing to launch. Folder: ${folder.displayName}, Account: ${account.username}, isRefresh: $isRefresh, Job active: ${messageFetchJob?.isActive}"
        )
        cancelAndClearJob("launchMessageFetchJob new fetch")

        val providerType = account.providerType.uppercase()
        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null || errorMapper == null) {
            Log.e(TAG, "launchMessageFetchJob: Cannot proceed. Missing service for $providerType")
            _messageDataState.value =
                MessageDataState.Error("Setup error for account type: $providerType")
            return
        }

        Log.i(
            TAG,
            "launchMessageFetchJob: Launching actual job for folder ${folder.displayName}/${account.username}. Provider: $providerType, Refresh: $isRefresh"
        )
        messageFetchJob = externalScope.launch(ioDispatcher) {
            Log.d(TAG, "[Job Coroutine - ${folder.displayName}] Starting fetch.")
            try {
                val messagesResult = mailApiService.getMessagesForFolder(
                    folder.id, messageListSelectFields, messageListPageSize
                )
                ensureActive()

                val newState = if (messagesResult.isSuccess) {
                    val messages = messagesResult.getOrThrow()
                    Log.i(TAG, "Fetched ${messages.size} messages for ${folder.displayName}")
                    MessageDataState.Success(messages)
                } else {
                    val exception = messagesResult.exceptionOrNull()
                        ?: IllegalStateException("Unknown error fetching messages")
                    Log.w(TAG, "Failed to fetch messages for ${folder.displayName}", exception)
                    val details = errorMapper.mapExceptionToErrorDetails(exception)
                    MessageDataState.Error(details.message)
                }
                if (isActive && currentTargetAccount?.id == account.id && currentTargetFolder?.id == folder.id) {
                    _messageDataState.value = newState
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "Message fetch job cancelled for ${folder.displayName}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during message fetch for ${folder.displayName}", e)
                if (isActive && currentTargetAccount?.id == account.id && currentTargetFolder?.id == folder.id) {
                    val details = errorMapper.mapExceptionToErrorDetails(e)
                    _messageDataState.value = MessageDataState.Error(details.message)
                }
            } finally {
                if (messageFetchJob == coroutineContext[Job]) {
                    messageFetchJob = null
                }
            }
        }

        messageFetchJob?.invokeOnCompletion { cause ->
            val folderIdentifier = currentTargetFolder?.displayName
                ?: folder.displayName // Use folder from closure if target changed
            Log.d(
                TAG,
                "Message fetch job for folder '$folderIdentifier' (Account: ${account.username}) completed. Cause: ${cause?.javaClass?.simpleName}"
            )
            if (messageFetchJob == null && cause is CancellationException && currentTargetAccount?.id == account.id && currentTargetFolder?.id == folder.id) {
                Log.i(
                    TAG,
                    "Message fetch job for '$folderIdentifier' was cancelled, possibly by a new target. Current state: ${_messageDataState.value}. No state change."
                )
                return@invokeOnCompletion
            }

            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                if (currentTargetAccount?.id == account.id && currentTargetFolder?.id == folder.id) {
                    Log.w(
                        TAG,
                        "Message fetch job for '$folderIdentifier' failed after completion. Cause: $cause"
                    )
                    val errorMapper = errorMappers[account.providerType.uppercase()]
                    if (errorMapper != null) {
                        val details = errorMapper.mapExceptionToErrorDetails(cause)
                        _messageDataState.value = MessageDataState.Error(details.message)
                    } else {
                        _messageDataState.value =
                            MessageDataState.Error("Failed to process error for ${account.providerType}: Mapper not found.")
                    }
                } else {
                    Log.d(
                        TAG,
                        "Message fetch job for '$folderIdentifier' (original target) completed with error, but target has changed. No state update."
                    )
                }
            } else if (cause == null && _messageDataState.value is MessageDataState.Loading && currentTargetAccount?.id == account.id && currentTargetFolder?.id == folder.id) {
                Log.w(
                    TAG,
                    "Message fetch job for '$folderIdentifier' completed without error but state is still Loading. Setting to error."
                )
                _messageDataState.value =
                    MessageDataState.Error("Unexpected completion state for ${folder.displayName}.")
            }
        }
    }

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "markMessageRead called for account: ${account.username}, messageId: $messageId, isRead: $isRead"
        )
        val providerType = account.providerType.uppercase()
        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null || errorMapper == null) {
            Log.e(
                TAG,
                "markMessageRead: Cannot proceed. Missing service for providerType '$providerType'."
            )
            return@withContext Result.failure(NotImplementedError("Service not available for provider: $providerType"))
        }

        try {
            val result = mailApiService.markMessageRead(messageId, isRead)
            if (result.isSuccess) {
                // Optionally, update local state if needed, e.g., refetch or update specific message in list
                Log.i(TAG, "markMessageRead successful for messageId: $messageId")
                // Trigger a silent refresh of the current folder's messages to reflect the change
                // Consider making this more targeted if possible
                if (account.id == currentTargetAccount?.id) {
                    launchMessageFetchJob(
                        account,
                        currentTargetFolder!!,
                        isRefresh = true,
                        activity = null
                    )
                }
            } else {
                Log.w(
                    TAG,
                    "markMessageRead failed for messageId: $messageId",
                    result.exceptionOrNull()
                )
            }
            return@withContext result.map { }
        } catch (e: Exception) {
            Log.e(TAG, "markMessageRead: Exception for messageId: $messageId", e)
            val details = errorMapper.mapExceptionToErrorDetails(e)
            return@withContext Result.failure(Exception(details.message))
        }
    }

    override suspend fun deleteMessage(account: Account, messageId: String): Result<Unit> =
        withContext(ioDispatcher) {
            Log.d(
                TAG,
                "deleteMessage called for account: ${account.username}, messageId: $messageId"
            )
            val providerType = account.providerType.uppercase()
            val mailApiService = mailApiServices[providerType]
            val errorMapper = errorMappers[providerType]

            if (mailApiService == null || errorMapper == null) {
                Log.e(
                    TAG,
                    "deleteMessage: Cannot proceed. Missing service for providerType '$providerType'."
                )
                return@withContext Result.failure(NotImplementedError("Service not available for provider: $providerType"))
            }

            try {
                val result = mailApiService.deleteMessage(messageId)
                if (result.isSuccess) {
                    Log.i(TAG, "deleteMessage successful for messageId: $messageId")
                    // Trigger a silent refresh
                    if (account.id == currentTargetAccount?.id && currentTargetFolder != null) {
                        launchMessageFetchJob(
                            account,
                            currentTargetFolder!!,
                            isRefresh = true,
                            activity = null
                        )
                }
                } else {
                    Log.w(
                        TAG,
                        "deleteMessage failed for messageId: $messageId",
                        result.exceptionOrNull()
                    )
            }
                return@withContext result.map { }
            } catch (e: Exception) {
                Log.e(TAG, "deleteMessage: Exception for messageId: $messageId", e)
                val details = errorMapper.mapExceptionToErrorDetails(e)
                return@withContext Result.failure(Exception(details.message))
        }
    }

    override suspend fun moveMessage(
        account: Account,
        messageId: String,
        destinationFolderId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "moveMessage called for account: ${account.username}, messageId: $messageId, destinationFolderId: $destinationFolderId"
        )
        val providerType = account.providerType.uppercase()
        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null || errorMapper == null) {
            Log.e(
                TAG,
                "moveMessage: Cannot proceed. Missing service for providerType '$providerType'."
            )
            return@withContext Result.failure(NotImplementedError("Service not available for provider: $providerType"))
        }

        try {
            val result = mailApiService.moveMessage(messageId, destinationFolderId)
            if (result.isSuccess) {
                Log.i(
                    TAG,
                    "moveMessage successful for messageId: $messageId to folder $destinationFolderId"
                )
                // Trigger a silent refresh
                if (account.id == currentTargetAccount?.id && currentTargetFolder != null) {
                    launchMessageFetchJob(
                        account,
                        currentTargetFolder!!,
                        isRefresh = true,
                        activity = null
                    )
                }
            } else {
                Log.w(TAG, "moveMessage failed for messageId: $messageId", result.exceptionOrNull())
            }
            return@withContext result.map { }
        } catch (e: Exception) {
            Log.e(TAG, "moveMessage: Exception for messageId: $messageId", e)
            val details = errorMapper.mapExceptionToErrorDetails(e)
            return@withContext Result.failure(Exception(details.message))
        }
    }

    private fun cancelAndClearJob(reason: String) {
        if (messageFetchJob?.isActive == true) {
            Log.d(TAG, "Cancelling active message fetch job. Reason: $reason")
            messageFetchJob?.cancel(CancellationException("New operation started: $reason"))
        }
        messageFetchJob = null
    }
}