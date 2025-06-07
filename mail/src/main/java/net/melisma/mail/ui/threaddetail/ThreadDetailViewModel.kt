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
                        evaluateAndTriggerDownloadsForThread(currentThreadState, accountId, bodyPref, online, wifi)
                    }
                }
            }.launchIn(viewModelScope)
        }

        viewModelScope.launch {
            threadIdFlow
                .flatMapLatest { currentThreadId ->
                    if (accountId.isNullOrBlank() || currentThreadId.isNullOrBlank()) {
                        _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Error("Account ID or Thread ID is missing")) }
                        flowOf<Result<MailThread>>()
                    } else {
                        Timber.d("Thread ID changed to: $currentThreadId or initial load.")
                        _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Loading) }
                        threadRepository.threadDataState.flatMapLatest { dataState ->
                            Timber.d("Current ThreadDataState: $dataState for threadId: $currentThreadId")
                            getThreadDetailsUseCase(threadId = currentThreadId, currentState = dataState)
                        }
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
                }.collectLatest { mailThreadResult: Result<MailThread> ->
                    mailThreadResult
                        .onSuccess { mailThread ->
                            if (accountId == null) {
                                _uiState.update { it.copy(threadLoadingState = ThreadDetailUIState.Error("Account ID became null")) }
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

                            val currentPrefs = _uiState.value
                            evaluateAndTriggerDownloadsForThread(
                                successState,
                                accountId,
                                currentPrefs.bodyDownloadPreference,
                                currentPrefs.isOnline,
                                currentPrefs.isWifiConnected
                            )
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
        bodyPref: DownloadPreference,
        isOnline: Boolean,
        isWifi: Boolean
    ) {
        val updatedMessages = threadSuccessState.threadMessages.map { item ->
            if (item.message.body.isNullOrBlank() && item.bodyState !is BodyLoadingState.Loaded && item.bodyState !is BodyLoadingState.Loading) {
                if (isOnline) {
                    if (bodyPref == DownloadPreference.ALWAYS ||
                        bodyPref == DownloadPreference.ON_WIFI ||
                        bodyPref == DownloadPreference.ON_DEMAND) {
                        enqueueMessageBodyDownload(item.message.id, currentAccountId)
                        item.copy(bodyState = BodyLoadingState.Loading)
                    } else {
                        item.copy(bodyState = BodyLoadingState.NotLoadedWillDownloadWhenOnline)
                    }
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

        if (messageItem.bodyState is BodyLoadingState.Loaded || messageItem.bodyState is BodyLoadingState.Loading) {
            Timber.d("requestMessageBody: Body for $messageIdToLoad already loaded or loading.")
            return
        }

        if (currentScreenState.isOnline) {
            if (currentScreenState.bodyDownloadPreference == DownloadPreference.ALWAYS ||
                currentScreenState.bodyDownloadPreference == DownloadPreference.ON_WIFI ||
                currentScreenState.bodyDownloadPreference == DownloadPreference.ON_DEMAND) {
                updateMessageBodyState(messageIdToLoad, BodyLoadingState.Loading)
                enqueueMessageBodyDownload(messageIdToLoad, accountId)
            } else {
                _uiState.update { it.copy(transientError = "Download not started due to preferences.") }
            }
        } else {
            updateMessageBodyState(messageIdToLoad, BodyLoadingState.NotLoadedOffline)
            _uiState.update { it.copy(transientError = "Cannot download body: No internet connection.") }
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
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
        observeWorkStatus(workRequest.id, messageId)
    }

    private fun observeWorkStatus(workId: UUID, messageId: String) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collectLatest { workInfo ->
                if (workInfo != null) {
                    Timber.d("WorkInfo for message body $messageId (ThreadDetail): ${workInfo.state}")
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val updatedMessage = (uiState.value.threadLoadingState as? ThreadDetailUIState.Success)
                                ?.threadMessages?.find { it.message.id == messageId }?.message?.copy(
                                body = workInfo.outputData.getString(MessageBodyDownloadWorker.KEY_RESULT_BODY) ?: ""
                            )

                            if (updatedMessage?.body?.isNotBlank() == true) {
                                 updateMessageBodyState(messageId, BodyLoadingState.Loaded(updatedMessage.body!!))
                            } else {
                                updateMessageBodyState(messageId, BodyLoadingState.Error("Body not found after download."))
                                Timber.w("Worker for $messageId succeeded but body is still blank.")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val errorData = workInfo.outputData.getString(MessageBodyDownloadWorker.KEY_RESULT_ERROR) ?: "Download failed"
                            updateMessageBodyState(messageId, BodyLoadingState.Error(errorData))
                        }
                        WorkInfo.State.CANCELLED -> {
                            updateMessageBodyState(messageId, BodyLoadingState.Error("Download cancelled"))
                        }
                        else -> { }
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