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
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
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
            Log.e(
                TAG,
                "launchMessageFetchJob: Cannot proceed. Missing service for providerType '$providerType'. " +
                        "MailApiService null? ${mailApiService == null}. " +
                        "ErrorMapper null? ${errorMapper == null}."
            )
            _messageDataState.value =
                MessageDataState.Error("Setup error for account type: $providerType")
            return
        }

        Log.i(
            TAG,
            "launchMessageFetchJob: Launching actual job for folder ${folder.displayName}/${account.username}. Provider: $providerType, Refresh: $isRefresh"
        )
        messageFetchJob = externalScope.launch(ioDispatcher) { // Launch in IO dispatcher
            Log.d(TAG, "[Job Coroutine - ${folder.displayName}] Starting fetch.")
            try {
                // Token acquisition is now handled internally by the Ktor Auth plugin
                // in both GmailApiHelper and GraphApiHelper
                account.providerType.uppercase()
                Log.i(
                    TAG,
                    "[Job Coroutine - ${folder.displayName}] Fetching messages for ${account.username}..."
                )

                // Both Google and Microsoft providers now handle tokens internally via Ktor Auth plugin
                // We can call the MailApiService methods directly without passing tokens
                val messagesResult = mailApiService.getMessagesForFolder(
                    folder.id, messageListSelectFields, messageListPageSize
                )
                ensureActive()
                Log.d(
                    TAG,
                    "[Job Coroutine - ${folder.displayName}] Messages result: isSuccess=${messagesResult.isSuccess}"
                )

                val newState = if (messagesResult.isSuccess) {
                    val messages = messagesResult.getOrThrow()
                    Log.i(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Successfully fetched ${messages.size} messages for ${folder.displayName}"
                    )
                    MessageDataState.Success(messages)
                } else {
                    val exception = messagesResult.exceptionOrNull()
                    val errorMsg = errorMapper.mapNetworkOrApiException(exception)
                    Log.e(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Failed to fetch messages: $errorMsg",
                        exception
                    )
                    MessageDataState.Error(errorMsg)
                }

                if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    Log.d(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Target still valid. Updating messageDataState to: $newState"
                    )
                    _messageDataState.value = newState
                } else {
                    Log.w(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Message fetch completed but target changed or coroutine inactive. Discarding result."
                    )
                }
            } catch (e: CancellationException) {
                Log.w(
                    TAG,
                    "[Job Coroutine - ${folder.displayName}] Message fetch job cancelled: ${e.message}",
                    e
                )
                if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id &&
                    _messageDataState.value is MessageDataState.Loading
                ) {
                    Log.d(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Resetting messageDataState to Initial due to cancellation while loading."
                    )
                    _messageDataState.value = MessageDataState.Initial
                }
                throw e
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "[Job Coroutine - ${folder.displayName}] Exception during message fetch: ${e.message}",
                    e
                )
                ensureActive()
                val errorMsg = errorMapper.mapNetworkOrApiException(e)
                if (isActive && account.id == currentTargetAccount?.id && folder.id == currentTargetFolder?.id) {
                    Log.d(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Target still valid after exception. Updating messageDataState to Error."
                    )
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                } else {
                    Log.w(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Exception caught but target changed or coroutine inactive. Discarding error."
                    )
                }
            } finally {
                Log.d(
                    TAG,
                    "[Job Coroutine - ${folder.displayName}] Finally block. Current job: ${coroutineContext[Job]?.hashCode()}, stored job: ${messageFetchJob?.hashCode()}"
                )
                if (messageFetchJob == coroutineContext[Job]) { // Check if this is the job instance that's completing
                    Log.d(
                        TAG,
                        "[Job Coroutine - ${folder.displayName}] Clearing messageFetchJob reference."
                    )
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
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(
                    TAG,
                    "Unhandled error completing message fetch job for '$folderIdentifier'.",
                    cause
                )
                if (account.id == currentTargetAccount?.id && currentTargetFolder?.id == folder.id &&
                    _messageDataState.value !is MessageDataState.Error
                ) { // Only update if not already an error from within the job
                    val providerErrorMapper = errorMappers[account.providerType.uppercase()]
                    val errorMsg = providerErrorMapper?.mapNetworkOrApiException(cause)
                        ?: "Unknown error after job completion for ${account.providerType}."
                    Log.d(TAG, "Updating messageDataState to Error from invokeOnCompletion.")
                    _messageDataState.value = MessageDataState.Error(errorMsg)
                }
            }
        }
    }

    private fun cancelAndClearJob(reason: String) {
        val jobToCancel = messageFetchJob
        if (jobToCancel != null) {
            Log.d(
                TAG,
                "cancelAndClearJob called. Reason: '$reason'. Current job active: ${jobToCancel.isActive}. Hash: ${jobToCancel.hashCode()}"
            )
            messageFetchJob = null // Clear the reference immediately
            if (jobToCancel.isActive) {
                jobToCancel.cancel(CancellationException("Job cancelled: $reason"))
                Log.i(
                    TAG,
                    "Previous message fetch job (Hash: ${jobToCancel.hashCode()}) cancelled due to: $reason"
                )
            }
        } else {
            Log.d(TAG, "cancelAndClearJob called. Reason: '$reason'. No active job to cancel.")
        }
    }
}