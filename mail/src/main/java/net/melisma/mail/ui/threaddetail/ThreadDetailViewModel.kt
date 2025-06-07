package net.melisma.mail.ui.threaddetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.preferences.DownloadPreference
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.data.sync.SyncEngine
import net.melisma.data.sync.workers.MessageBodyDownloadWorker
import net.melisma.domain.data.GetThreadDetailsUseCase
import net.melisma.mail.navigation.AppRoutes
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class ThreadDetailScreenState(
    val threadLoadingState: ThreadDetailUIState = ThreadDetailUIState.Loading,
    val bodyDownloadPreference: DownloadPreference = DownloadPreference.ALWAYS,
    val isOnline: Boolean = false,
    val isWifiConnected: Boolean = false,
    val transientError: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadDetailViewModel @Inject constructor(
    private val getThreadDetailsUseCase: GetThreadDetailsUseCase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val networkMonitor: NetworkMonitor,
    private val workManager: WorkManager,
    private val threadRepository: ThreadRepository,
    private val syncEngine: SyncEngine,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThreadDetailScreenState())
    val uiState: StateFlow<ThreadDetailScreenState> = _uiState

    private val accountId: String? = savedStateHandle[AppRoutes.ARG_ACCOUNT_ID]
    private val threadIdFlow: StateFlow<String?> = savedStateHandle.getStateFlow(AppRoutes.ARG_THREAD_ID, null)

    init {
        Timber.d("ThreadDetailViewModel init. Account: $accountId")

        viewModelScope.launch {
            combine(
                userPreferencesRepository.userPreferencesFlow,
                networkMonitor.isOnline,
                networkMonitor.isWifiConnected,
                threadIdFlow
            ) { prefs, online, wifi, currentThreadId ->
                Triple(prefs.bodyDownloadPreference, online, wifi) to currentThreadId
            }.onEach { (prefsAndNetwork, currentThreadId) ->
                val (bodyPref, online, wifi) = prefsAndNetwork
                val oldState = _uiState.value
                _uiState.update {
                    it.copy(
                        bodyDownloadPreference = bodyPref,
                        isOnline = online,
                        isWifiConnected = wifi
                    )
                }
                if (oldState.isOnline != online || oldState.isWifiConnected != wifi || oldState.bodyDownloadPreference != bodyPref) {
                    val currentThreadState = _uiState.value.threadLoadingState
                    if (currentThreadState is ThreadDetailUIState.Success && accountId != null) {
                        evaluateAndTriggerDownloadsForThread(currentThreadState, accountId, online, wifi)
                    }
                }
            }.launchIn(viewModelScope)
        }

        viewModelScope.launch {
            threadIdFlow.combine(threadRepository.threadDataState) { id, state ->
                Pair(id, state)
            }.flatMapLatest { (currentThreadId, currentDataState) ->
                if (accountId.isNullOrBlank() || currentThreadId.isNullOrBlank()) {
                    _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Error("Account ID or Thread ID is missing")) }
                    flowOf(Result.failure<MailThread>(IllegalStateException("Account ID or Thread ID is missing")))
                } else {
                    Timber.d("Observing thread: $currentThreadId with data state: $currentDataState")
                    if (currentDataState is ThreadDataState.Initial || currentDataState is ThreadDataState.Loading) {
                        _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Loading) }
                    }
                    getThreadDetailsUseCase(currentThreadId, currentDataState)
                }
            }.catch { exception ->
                Timber.w(exception, "Error in thread details collection flow: ${exception.message}")
                _uiState.update {
                    it.copy(
                        threadLoadingState = ThreadDetailUIState.Error(
                            exception.message ?: "Error observing thread details"
                        )
                    )
                }
            }.collectLatest { mailThreadResult: Result<MailThread?> ->
                mailThreadResult
                    .onSuccess { mailThread ->
                        if (mailThread == null) {
                            Timber.e("CRITICAL: MailThread is null in onSuccess from GetThreadDetailsUseCase for threadId ${threadIdFlow.value}. This indicates an issue with GetThreadDetailsUseCase\'s success emission or flow typing.")
                            _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Error("Thread data unexpectedly null after success")) }
                            return@onSuccess
                        }
                        if (accountId == null) {
                            _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Error("Account ID became null while processing thread")) }
                            return@onSuccess
                        }
                        val initialItems = mailThread.messages.map { msg ->
                            ThreadMessageItem(message = msg, bodyState = determineInitialBodyState(msg))
                        }
                        val successState = ThreadDetailUIState.Success(
                            threadMessages = initialItems,
                            threadSubject = mailThread.subject,
                            accountId = accountId,
                            threadId = mailThread.id
                        )
                        _uiState.update { it.copy(threadLoadingState = successState) }

                        val currentScreenState = _uiState.value
                        evaluateAndTriggerDownloadsForThread(
                            successState,
                            accountId,
                            currentScreenState.isOnline,
                            currentScreenState.isWifiConnected
                        )
                        successState.threadMessages.forEach { item ->
                            triggerAutoRefreshIfNeeded(item.message, accountId, currentScreenState.isOnline)
                        }
                    }
                    .onFailure { exception ->
                        Timber.w(exception, "Failed to get MailThread: ${exception.message}")
                        _uiState.update {
                            it.copy(
                                threadLoadingState = ThreadDetailUIState.Error(
                                    exception.message ?: "Thread not found or error fetching"
                                )
                            )
                        }
                    }
            }
        }
    }

    private fun determineInitialBodyState(message: Message): BodyLoadingState {
        return if (!message.body.isNullOrBlank()) {
            BodyLoadingState.Loaded(message.body!!)
        } else {
            BodyLoadingState.Initial
        }
    }

    private fun evaluateAndTriggerDownloadsForThread(
        threadSuccessState: ThreadDetailUIState.Success,
        currentAccountId: String,
        isOnline: Boolean,
        isWifi: Boolean
    ) {
        val bodyPref = _uiState.value.bodyDownloadPreference
        val updatedMessages = threadSuccessState.threadMessages.map { item ->
            if (item.message.body.isNullOrBlank() && item.bodyState !is BodyLoadingState.Loaded && item.bodyState !is BodyLoadingState.Loading) {
                if (isOnline) {
                    Timber.i("TDB_VM: Active view (thread), online, body missing for ${item.message.id}. Forcing download.")
                    enqueueMessageBodyDownload(item.message.id, currentAccountId)
                    if (bodyPref == DownloadPreference.ON_WIFI && !isWifi) {
                         Timber.e("TDB_VM_DIRE_ERROR: Body download for ${item.message.id} (thread) initiated on non-WiFi, but preference was ON_WIFI. This is an override for active view. Logging for awareness.")
                    }
                    item.copy(bodyState = BodyLoadingState.Loading)
                } else {
                    item.copy(bodyState = BodyLoadingState.NotLoadedOffline)
                }
            } else if (!item.message.body.isNullOrBlank() && item.bodyState !is BodyLoadingState.Loaded) {
                 item.copy(bodyState = BodyLoadingState.Loaded(item.message.body!!))
            }
             else {
                item
            }
        }
        _uiState.update { it.copy(threadLoadingState = threadSuccessState.copy(threadMessages = updatedMessages)) }
    }

    fun requestMessageBody(messageIdToLoad: String) {
        val currentScreenState = _uiState.value
        val currentDetailState = currentScreenState.threadLoadingState

        if (accountId == null) {
            Timber.e("requestMessageBody: AccountId is null for $messageIdToLoad")
            _uiState.update { it.copy(transientError = "Account info missing.") }
            return
        }
        if (currentDetailState !is ThreadDetailUIState.Success) {
            Timber.w("requestMessageBody: Not in Success state for $messageIdToLoad")
            _uiState.update { it.copy(transientError = "Thread details not fully loaded.") }
            return
        }

        val messageItem = currentDetailState.threadMessages.find { it.message.id == messageIdToLoad }
        if (messageItem == null) {
            Timber.w("requestMessageBody: Message $messageIdToLoad not found.")
            _uiState.update { it.copy(transientError = "Message not found in thread.") }
            return
        }

        triggerAutoRefreshIfNeeded(messageItem.message, accountId, currentScreenState.isOnline)

        if (messageItem.bodyState is BodyLoadingState.Loaded || messageItem.bodyState is BodyLoadingState.Loading) {
            Timber.d("requestMessageBody: Body for $messageIdToLoad already loaded or loading.")
            return
        }

        if (currentScreenState.isOnline) {
            Timber.i("TDB_VM: Explicit request for body ${messageItem.message.id}. Forcing download.")
            updateMessageBodyState(messageIdToLoad, BodyLoadingState.Loading)
            enqueueMessageBodyDownload(messageIdToLoad, accountId)
            if (currentScreenState.bodyDownloadPreference == DownloadPreference.ON_WIFI && !currentScreenState.isWifiConnected) {
                 Timber.e("TDB_VM_DIRE_ERROR: Body download for ${messageItem.message.id} (explicit request) initiated on non-WiFi, but preference was ON_WIFI. Override for active view. Logging for awareness.")
            }
        } else {
            updateMessageBodyState(messageIdToLoad, BodyLoadingState.NotLoadedOffline)
            _uiState.update { it.copy(transientError = "Cannot download body: No internet connection.") }
        }
    }

    private fun triggerAutoRefreshIfNeeded(message: Message, accountId: String, isOnline: Boolean) {
        if (isOnline && !message.remoteId.isNullOrBlank()) {
            val lastSync = message.lastSuccessfulSyncTimestamp
            val staleThresholdMs = 5 * 60 * 1000L // 5 minutes
            val isStale = lastSync == null || (System.currentTimeMillis() - lastSync) > staleThresholdMs

            if (isStale) {
                Timber.d("TDB_VM: Message ${message.remoteId} (local: ${message.id}) in thread is stale (last sync: $lastSync). Triggering silent refresh.")
                syncEngine.refreshMessage(accountId, message.id, message.remoteId)
            } else {
                Timber.d("TDB_VM: Message ${message.remoteId} (local: ${message.id}) in thread is fresh (last sync: $lastSync). No silent refresh needed.")
            }
        } else {
            Timber.d("TDB_VM: Skipping silent refresh for ${message.id} in thread. Online: $isOnline, RemoteId: ${message.remoteId}")
        }
    }

    private fun enqueueMessageBodyDownload(messageId: String, accId: String) {
        Timber.d("Enqueuing MessageBodyDownloadWorker for $messageId in account $accId (ThreadDetail)")
        val workRequest = OneTimeWorkRequestBuilder<MessageBodyDownloadWorker>()
            .setInputData(
                workDataOf(
                    MessageBodyDownloadWorker.KEY_ACCOUNT_ID to accId,
                    MessageBodyDownloadWorker.KEY_MESSAGE_ID to messageId
                )
            )
            .build()
        val workName = "thread-message-body-download-$messageId"
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)
        observeWorkStatus(workRequest.id, messageId)
    }

    private fun observeWorkStatus(workId: UUID, messageId: String) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collectLatest { workInfo ->
                if (workInfo != null) {
                    Timber.d("WorkInfo for message body $messageId (ThreadDetail): ${workInfo.state}")
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            Timber.d("TDB_VM: Body download for $messageId SUCCEEDED. Data will refresh via observer.")
                        }
                        WorkInfo.State.FAILED -> {
                            val errorData = workInfo.outputData.getString(MessageBodyDownloadWorker.KEY_RESULT_ERROR) ?: "Download failed"
                            updateMessageBodyState(messageId, BodyLoadingState.Error(errorData))
                        }
                        WorkInfo.State.CANCELLED -> {
                            updateMessageBodyState(messageId, BodyLoadingState.Error("Download cancelled"))
                        }
                        else -> { /* RUNNING, ENQUEUED, BLOCKED - bodyState is already Loading */ }
                    }
                }
            }
        }
    }

    private fun updateMessageBodyState(messageId: String, newBodyState: BodyLoadingState) {
        _uiState.update { currentState ->
            val currentLoadingState = currentState.threadLoadingState
            if (currentLoadingState is ThreadDetailUIState.Success) {
                currentState.copy(
                    threadLoadingState = currentLoadingState.copy(
                        threadMessages = currentLoadingState.threadMessages.map { item ->
                            if (item.message.id == messageId) item.copy(bodyState = newBodyState) else item
                        }
                    )
                )
            } else {
                currentState
            }
        }
    }

    fun clearTransientError() {
        _uiState.update { it.copy(transientError = null) }
    }
} 