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
import net.melisma.core_data.datasource.TokenProvider
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
    private val tokenProviders: Map<String, @JvmSuppressWildcards TokenProvider>,
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : MessageRepository {

    private val TAG = "DefaultMessageRepo"

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

            Log.i(
                TAG,
                "Setting target folder: Account=${account?.username}, Folder=${folder?.displayName}"
            ) // CORRECTED: account.username, folder.displayName
            cancelAndClearJob()

            currentTargetAccount = account
            currentTargetFolder = folder

            if (account == null || folder == null) {
                _messageDataState.value = MessageDataState.Initial
            } else {
                val providerType = account.providerType
                if (tokenProviders.containsKey(providerType) &&
                    mailApiServices.containsKey(providerType) &&
                    errorMappers.containsKey(providerType.uppercase())
                ) { // Ensure key lookup is case-insensitive if keys are uppercase
                    _messageDataState.value = MessageDataState.Loading
                    launchMessageFetchJob(account, folder, isRefresh = false, activity = null)
                } else {
                    Log.w(
                        TAG,
                        "Unsupported account provider type or missing service for: $providerType. Available mappers: ${errorMappers.keys}"
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

        if (account == null || folder == null) {
            Log.w(TAG, "refreshMessages called but no target folder is set.")
            return
        }

        val providerType = account.providerType
        if (tokenProviders.containsKey(providerType) &&
            mailApiServices.containsKey(providerType) &&
            errorMappers.containsKey(providerType.uppercase())
        ) { // Ensure key lookup
            refreshMessagesForProvider(account, folder, activity)
        } else {
            Log.w(
                TAG,
                "refreshMessages called for unsupported account type or missing service for: $providerType. Available mappers: ${errorMappers.keys}"
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
                "Refresh skipped: Already loading messages for folder: ${folder.displayName}."
            ) // CORRECTED: folder.displayName
            return
        }
        Log.d(
            TAG,
            "Refreshing messages for folder: ${folder.displayName}, account: ${account.username}"
        ) // CORRECTED: folder.displayName, account.username
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
        cancelAndClearJob()

        val providerType =
            account.providerType.uppercase() // Use uppercase for map lookup consistency
        val tokenProvider = tokenProviders[providerType]
        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (tokenProvider == null || mailApiService == null || errorMapper == null) {
            Log.e(
                TAG,
                "No provider, API service, or error mapper available for account type: $providerType. Available mappers: ${errorMappers.keys}"
            )
            _messageDataState.value =
                MessageDataState.Error("Setup error for account type: $providerType")
            return
        }

        Log.d(
            TAG,
            "Launching message fetch job for folder ${folder.displayName}/${account.username}. Refresh: $isRefresh, Provider: $providerType"
        ) // CORRECTED: folder.displayName, account.username
        messageFetchJob = externalScope.launch(ioDispatcher) {
            try {
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive()

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(
                        TAG,
                        "Token acquired for ${account.username}, fetching messages..."
                    ) // CORRECTED: account.username

                    val messagesResult = mailApiService.getMessagesForFolder(
                        accessToken, folder.id, messageListSelectFields, messageListPageSize
                    )
                    ensureActive()

                    val newState = if (messagesResult.isSuccess) {
                        val messages = messagesResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${messages.size} messages for ${folder.displayName}"
                        ) // CORRECTED: folder.displayName
                        MessageDataState.Success(messages)
                    } else {
                        val errorMsg =
                            errorMapper.mapNetworkOrApiException(messagesResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch messages for ${folder.displayName}: $errorMsg",
                            messagesResult.exceptionOrNull()
                        ) // CORRECTED: folder.displayName
                        MessageDataState.Error(errorMsg)
                    }

                    if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = newState
                    } else {
                        Log.w(
                            TAG,
                            "Message fetch completed but target changed or coroutine inactive. Discarding result for ${folder.displayName}"
                        ) // CORRECTED: folder.displayName
                    }
                } else {
                    val errorMsg =
                        errorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for messages: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive()
                    if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                        _messageDataState.value = MessageDataState.Error(errorMsg)
                    } else {
                        Log.w(
                            TAG,
                            "Token error received but target changed or coroutine inactive. Discarding error for ${folder.displayName}"
                        ) // CORRECTED: folder.displayName
                    }
                }
            } catch (e: CancellationException) {
                Log.w(
                    TAG,
                    "Message fetch job for ${folder.displayName}/${account.username} cancelled.",
                    e
                ) // CORRECTED: folder.displayName, account.username
                if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value is MessageDataState.Loading
                ) {
                    _messageDataState.value =
                        MessageDataState.Initial // Assuming MessageDataState.Initial exists and is correct
                }
                throw e
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Exception during message fetch for ${folder.displayName}/${account.username}",
                    e
                ) // CORRECTED: folder.displayName, account.username
                ensureActive()
                val errorMsg = errorMapper.mapNetworkOrApiException(e)
                if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but target changed or coroutine inactive. Discarding error for ${folder.displayName}"
                    ) // CORRECTED: folder.displayName
                }
            } finally {
                if (messageFetchJob == coroutineContext[Job]) {
                    messageFetchJob = null
                }
            }
        }

        messageFetchJob?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                val folderIdentifier = currentTargetFolder?.displayName
                    ?: "unknown folder" // CORRECTED: folder.displayName
                Log.e(
                    TAG,
                    "Unhandled error completing message fetch job for $folderIdentifier",
                    cause
                )
                if (account.id == currentTargetAccount?.id && currentTargetFolder?.id == folder.id && // check folder id too
                    _messageDataState.value !is MessageDataState.Error
                ) {
                    val providerErrorMapper =
                        errorMappers[account.providerType.uppercase()] // Use uppercase for consistency
                    val errorMsg = providerErrorMapper?.mapNetworkOrApiException(cause)
                        ?: "Unknown error after job completion for $providerType."
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