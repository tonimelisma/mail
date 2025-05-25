package net.melisma.data.repository

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultMessageRepository @Inject constructor(
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val accountRepository: AccountRepository
) : MessageRepository {

    private val TAG = "DefaultMessageRepo"
    private val _messageDataState = MutableStateFlow<MessageDataState>(MessageDataState.Initial)
    override val messageDataState: StateFlow<MessageDataState> = _messageDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    private var fetchJob: Job? = null

    init {
        Log.d(
            TAG, "Initializing DefaultMessageRepository. Injected maps:" +
                    " mailApiServices keys: ${mailApiServices.keys}, errorMappers keys: ${errorMappers.keys}"
        )
    }

    override suspend fun setTargetFolder(
        account: Account?,
        folder: MailFolder?
    ) {
        Log.d(
            TAG,
            "setTargetFolder: Account=${account?.username}, Folder=${folder?.displayName}, Job Active: ${fetchJob?.isActive}"
        )

        val isSameTarget =
            account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id
        val isInitialOrError =
            _messageDataState.value is MessageDataState.Initial || _messageDataState.value is MessageDataState.Error

        if (isSameTarget && !isInitialOrError) {
            Log.d(
                TAG,
                "setTargetFolder: Same target and data already loaded/loading and not in error/initial state. To refresh, call refreshMessages()."
            )
            return
        }

        if (isSameTarget && _messageDataState.value is MessageDataState.Loading) {
            Log.d(TAG, "setTargetFolder: Same target and already loading. Ignoring.")
            return
        }


        cancelAndClearJob("New target folder: ${folder?.displayName ?: "null"}")
        currentTargetAccount = account
        currentTargetFolder = folder

        if (account == null || folder == null) {
            _messageDataState.value = MessageDataState.Initial
            Log.d(TAG, "setTargetFolder: Cleared target, state set to Initial.")
            return
        }
        _messageDataState.value = MessageDataState.Loading
        launchMessageFetchJobInternal(account, folder, isRefresh = false)
    }

    private fun launchMessageFetchJobInternal(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean
    ) {
        cancelAndClearJob("Launching new message fetch for ${folder.displayName}. Refresh: $isRefresh")
        Log.d(
            TAG,
            "[${folder.displayName}] launchMessageFetchJobInternal for account type: ${account.providerType}"
        )

        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (apiService == null || errorMapper == null) {
            val errorMsg =
                "Unsupported account type: $providerType or missing services for message fetching."
            Log.e(TAG, errorMsg)
            _messageDataState.value = MessageDataState.Error(errorMsg)
            return
        }

        fetchJob = externalScope.launch(ioDispatcher) {
            Log.i(TAG, "[${folder.displayName}] Message fetch job started. Refresh: $isRefresh")
            try {
                val messagesResult = apiService.getMessagesForFolder(
                    folderId = folder.id,
                    maxResults = 50 // Default page size
                )
                ensureActive()

                if (messagesResult.isSuccess) {
                    val messages = messagesResult.getOrThrow()
                    Log.i(
                        TAG,
                        "[${folder.displayName}] Successfully fetched ${messages.size} messages."
                    )
                    _messageDataState.value = MessageDataState.Success(messages)
                } else {
                    val exception = messagesResult.exceptionOrNull()
                    val errorDetails = errorMapper.mapExceptionToErrorDetails(exception)
                    Log.e(
                        TAG,
                        "[${folder.displayName}] Error fetching messages: ${errorDetails.message}",
                        exception
                    )
                    _messageDataState.value = MessageDataState.Error(errorDetails.message)
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "[${folder.displayName}] Message fetch job cancelled.", e)
                if (isActive && currentTargetFolder?.id == folder.id && _messageDataState.value is MessageDataState.Loading) {
                    _messageDataState.value = MessageDataState.Error("Message loading cancelled.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[${folder.displayName}] Exception during message fetch process", e)
                if (isActive && currentTargetFolder?.id == folder.id) {
                    val details = errorMapper.mapExceptionToErrorDetails(e)
                    _messageDataState.value = MessageDataState.Error(details.message)
                }
            } finally {
                if (fetchJob == coroutineContext[Job]) {
                    fetchJob = null
                }
            }
        }
    }

    override suspend fun refreshMessages(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder
        if (account == null || folder == null) {
            Log.w(TAG, "refreshMessages: No target account or folder set. Skipping.")
            _messageDataState.value = MessageDataState.Error("Cannot refresh: No folder selected.")
            return
        }
        Log.d(
            TAG,
            "refreshMessages called for folder: ${folder.displayName}, Account: ${account.username}"
        )
        if (_messageDataState.value is MessageDataState.Loading) {
            Log.d(TAG, "refreshMessages: Already loading. Skipping duplicate refresh.")
            return
        }
        _messageDataState.value = MessageDataState.Loading
        launchMessageFetchJobInternal(account, folder, isRefresh = true)
    }


    private fun cancelAndClearJob(reason: String) {
        fetchJob?.let {
            if (it.isActive) {
                Log.d(
                    TAG,
                    "Cancelling previous message fetch job. Reason: $reason. Hash: ${it.hashCode()}"
                )
                it.cancel(CancellationException("Job cancelled: $reason"))
            }
        }
        fetchJob = null
        Log.d(TAG, "cancelAndClearJob: Cleared fetchJob. Reason: $reason")
    }

    override suspend fun getMessageDetails(messageId: String, accountId: String): Flow<Message?> {
        Log.d(TAG, "getMessageDetails called for messageId: $messageId, accountId: $accountId")

        val accountsList: List<Account>? = accountRepository.getAccounts().firstOrNull()
        val accounts: List<Account> = accountsList ?: emptyList()
        val account: Account? = accounts.find { acc -> acc.id == accountId }

        if (account == null) {
            Log.e(TAG, "getMessageDetails: Account not found for id $accountId")
            return flow { emit(null) }
        }
        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        if (apiService == null) {
            Log.e(
                TAG,
                "getMessageDetails: ApiService not found for provider ${account.providerType}"
            )
            return flow { emit(null) }
        }
        return apiService.getMessageDetails(messageId)
    }

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> {
        Log.d(
            TAG,
            "markMessageRead called for id: $messageId, account: ${account.username}, isRead: $isRead"
        )
        val apiService = mailApiServices[account.providerType.uppercase()]
            ?: return Result.failure(NotImplementedError("markMessageRead not implemented for account ${account.providerType}"))

        val result = apiService.markMessageRead(messageId, isRead)
        if (result.isSuccess) {
            _messageDataState.update { currentState ->
                if (currentState is MessageDataState.Success) {
                    val updatedMessages = currentState.messages.map { message ->
                        if (message.id == messageId) message.copy(isRead = isRead) else message
                    }
                    currentState.copy(messages = updatedMessages)
                } else {
                    currentState
                }
            }
        }
        return result
    }

    override suspend fun deleteMessage(account: Account, messageId: String): Result<Unit> {
        Log.d(TAG, "deleteMessage called for id: $messageId, account: ${account.username}")
        val apiService = mailApiServices[account.providerType.uppercase()]
            ?: return Result.failure(NotImplementedError("deleteMessage not implemented for account ${account.providerType}"))

        val result = apiService.deleteMessage(messageId)
        if (result.isSuccess) {
            _messageDataState.update { currentState ->
                if (currentState is MessageDataState.Success) {
                    currentState.copy(messages = currentState.messages.filterNot { it.id == messageId })
                } else {
                    currentState
                }
            }
        }
        return result
    }

    override suspend fun moveMessage(
        account: Account,
        messageId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Log.d(
            TAG,
            "moveMessage called for id: $messageId, to folder: $destinationFolderId, account: ${account.username}"
        )
        val apiService = mailApiServices[account.providerType.uppercase()]
            ?: return Result.failure(NotImplementedError("moveMessage not implemented for account ${account.providerType}"))

        val currentFolderId = currentTargetFolder?.id
        if (currentFolderId == null) {
            Log.e(
                TAG,
                "moveMessage: currentFolderId is null, cannot determine source folder for API call."
            )
            return Result.failure(IllegalStateException("Current folder not set, cannot move message."))
        }

        val result = apiService.moveMessage(messageId, currentFolderId, destinationFolderId)
        if (result.isSuccess) {
            _messageDataState.update { currentState ->
                if (currentState is MessageDataState.Success) {
                    currentState.copy(messages = currentState.messages.filterNot { it.id == messageId })
                } else {
                    currentState
                }
            }
        }
        return result
    }
}