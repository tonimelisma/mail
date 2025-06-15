package net.melisma.mail.ui.messagedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.SyncJob
import net.melisma.core_data.preferences.DownloadPreference
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.data.sync.SyncController
import net.melisma.domain.data.GetMessageDetailsUseCase
import net.melisma.mail.navigation.AppRoutes
import timber.log.Timber
import javax.inject.Inject

// The sealed interface MessageDetailUIState that was here has been removed.
// It should be defined in its own file: MessageDetailUIState.kt

// New detailed display state enums
enum class ContentDisplayState {
    LOADING, // Initial loading of message details
    DOWNLOADING,
    DOWNLOADED,
    NOT_DOWNLOADED_WILL_DOWNLOAD_ON_WIFI, // This state should not be used for active view if online
    NOT_DOWNLOADED_WILL_DOWNLOAD_WHEN_ONLINE,
    NOT_DOWNLOADED_OFFLINE, // Device is offline, and content not present
    NOT_DOWNLOADED_PREFERENCE_ON_DEMAND, // Placeholder if ON_DEMAND is ever re-introduced for other features
    ERROR
}

data class MessageDetailScreenState(
    val messageOverallState: MessageDetailUIState = MessageDetailUIState.Loading,
    val bodyDisplayState: ContentDisplayState = ContentDisplayState.LOADING,
    val attachments: List<Attachment> = emptyList(), // Replaces attachmentDisplayStates
    // Preferences and network state, still needed for logic
    val bodyDownloadPreference: DownloadPreference = DownloadPreference.ALWAYS,
    val attachmentDownloadPreference: DownloadPreference = DownloadPreference.ON_WIFI,
    val isOnline: Boolean = false,
    val isWifiConnected: Boolean = false, // Still needed to evaluate ON_WIFI preference
    val transientError: String? = null
) {
    val isBodyLocallyAvailable: Boolean
        get() = (messageOverallState as? MessageDetailUIState.Success)?.message?.body != null && bodyDisplayState == ContentDisplayState.DOWNLOADED

    val message: Message?
        get() = (messageOverallState as? MessageDetailUIState.Success)?.message
}


@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    private val getMessageDetailsUseCase: GetMessageDetailsUseCase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncController: SyncController,
    private val attachmentDao: AttachmentDao, // Inject AttachmentDao
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val accountId: String? = savedStateHandle[AppRoutes.ARG_ACCOUNT_ID]
    private val messageId: String? = savedStateHandle[AppRoutes.ARG_MESSAGE_ID]

    private val _uiState = MutableStateFlow(MessageDetailScreenState())
    val uiState: StateFlow<MessageDetailScreenState> = _uiState

    init {
        Timber.d("ViewModelDBG: MessageDetailViewModel init. AccountId: $accountId, MessageId: $messageId")

        viewModelScope.launch {
            combine(
                userPreferencesRepository.userPreferencesFlow,
                networkMonitor.isOnline,
                networkMonitor.isWifiConnected
            ) { prefs, online, wifi ->
                Triple(prefs, online, wifi)
            }.onEach { (prefs, online, wifi) ->
                val previousState = _uiState.value
                _uiState.update { currentState ->
                    currentState.copy(
                        bodyDownloadPreference = prefs.bodyDownloadPreference,
                        attachmentDownloadPreference = prefs.attachmentDownloadPreference,
                        isOnline = online,
                        isWifiConnected = wifi
                    )
                }
                // Re-evaluate download states if network or preferences changed
                if (previousState.isOnline != online || previousState.isWifiConnected != wifi ||
                    previousState.bodyDownloadPreference != prefs.bodyDownloadPreference ||
                    previousState.attachmentDownloadPreference != prefs.attachmentDownloadPreference) {
                    _uiState.value.message?.let { msg ->
                        evaluateAndTriggerDownloads(msg, online, wifi)
                    }
                }
            }.launchIn(viewModelScope)
        }

        if (accountId != null && messageId != null) {
            loadMessageDetails(accountId, messageId)
        } else {
            Timber.w("ViewModelDBG: Account ID or Message ID is missing. Emitting Error state.")
            _uiState.update { it.copy(messageOverallState = MessageDetailUIState.Error("Account ID or Message ID is missing")) }
        }
    }

    private fun loadMessageDetails(accountId: String, messageId: String) {
        viewModelScope.launch {
            Timber.d("ViewModelDBG: Launching coroutine to call getMessageDetailsUseCase for $messageId")

            // Create a flow that combines message details with its attachments
            val messageWithAttachmentsFlow = getMessageDetailsUseCase(messageId, accountId)
                .flatMapLatest { message ->
                    if (message == null) {
                        flowOf(null to emptyList<Attachment>())
                    } else {
                        attachmentDao.getAttachmentsForMessage(message.id).map { attachments ->
                            message to attachments
                        }
                    }
                }

            messageWithAttachmentsFlow
                .onStart {
                    Timber.d("ViewModelDBG: UseCase Flow started for $messageId. Emitting Loading state.")
                    _uiState.update { it.copy(messageOverallState = MessageDetailUIState.Loading, bodyDisplayState = ContentDisplayState.LOADING) }
                }
                .catch { exception ->
                    Timber.e(exception, "ViewModelDBG: UseCase Flow caught error for $messageId.")
                    _uiState.update {
                        it.copy(
                            messageOverallState = MessageDetailUIState.Error(
                                exception.message ?: "An unknown error occurred loading message"
                            ),
                            bodyDisplayState = ContentDisplayState.ERROR
                        )
                    }
                }
                .collect { pair ->
                    val message = pair.first
                    val attachments = pair.second as List<Attachment>
                    if (message != null) {
                        Timber.d("ViewModelDBG: UseCase Flow collected message for $messageId. Subject: '${message.subject}'. Attachments: ${attachments.size}")
                        _uiState.update { currentState ->
                            currentState.copy(
                                messageOverallState = MessageDetailUIState.Success(message),
                                attachments = attachments // Update attachments in the state
                            )
                        }
                        val currentUiState = _uiState.value
                        evaluateAndTriggerDownloads(
                            message,
                            currentUiState.isOnline,
                            currentUiState.isWifiConnected
                        )

                        // Auto-refresh logic
                        if (currentUiState.isOnline && this@MessageDetailViewModel.accountId != null && !message.remoteId.isNullOrBlank() && currentUiState.bodyDisplayState != ContentDisplayState.DOWNLOADING) {
                            val lastSync = message.lastSuccessfulSyncTimestamp
                            val staleThresholdMs = 5 * 60 * 1000L // 5 minutes
                            val isStale = lastSync == null || (System.currentTimeMillis() - lastSync) > staleThresholdMs

                            if (isStale) {
                                Timber.d("ViewModelDBG: Message ${message.remoteId} (local: ${message.id}) is stale (last sync: $lastSync). Triggering silent refresh.")
                                message.remoteId?.let { remoteId ->
                                    syncController.submit(SyncJob.FetchFullMessageBody(remoteId, this@MessageDetailViewModel.accountId!!))
                                } ?: Timber.w("Message ${message.id} has null remoteId; cannot request body refresh via SyncController.")
                            } else {
                                Timber.d("ViewModelDBG: Message ${message.remoteId} (local: ${message.id}) is fresh (last sync: $lastSync). No silent refresh needed.")
                            }
                        } else {
                             Timber.d("ViewModelDBG: Skipping silent refresh for ${message.id}. Online: ${currentUiState.isOnline}, AccountId: ${this@MessageDetailViewModel.accountId}, RemoteId: ${message.remoteId}")
                        }

                    } else {
                        Timber.d("ViewModelDBG: UseCase Flow collected NULL message for $messageId. Emitting Error state - Message not found.")
                        _uiState.update { it.copy(messageOverallState = MessageDetailUIState.Error("Message not found"), bodyDisplayState = ContentDisplayState.ERROR) }
                    }
                }
        }
    }

    private fun evaluateAndTriggerDownloads(
        message: Message,
        isOnline: Boolean,
        isWifi: Boolean
    ) {
        var finalBodyState: ContentDisplayState
        _uiState.value.bodyDownloadPreference

        // Evaluate Body
        if (!message.body.isNullOrBlank()) {
            finalBodyState = ContentDisplayState.DOWNLOADED
        } else { // Body is blank, needs decision
            if (isOnline) {
                // ACTIVE VIEW & ONLINE: Always attempt to download the body immediately.
                finalBodyState = ContentDisplayState.DOWNLOADING
                Timber.i("ViewModelDBG: Active view, online, body missing for ${message.id}. Forcing download.")
                val existingMessageId = _uiState.value.message?.id
                if (_uiState.value.bodyDisplayState != ContentDisplayState.DOWNLOADING || existingMessageId != message.id) {
                    enqueueMessageBodyDownloadInternal(message.id, accountId!!)
                } else {
                    Timber.d("ViewModelDBG: Body download already marked as DOWNLOADING for ${message.id}. Skipping re-enqueue.")
                }
            } else { // Device is OFFLINE
                finalBodyState = ContentDisplayState.NOT_DOWNLOADED_OFFLINE
            }
        }
        _uiState.update { it.copy(bodyDisplayState = finalBodyState) }
    }

    private fun enqueueMessageBodyDownloadInternal(msgId: String, accId: String) {
        // Check if already downloading to avoid re-enqueuing unnecessarily
        // This check would be more robust by observing WorkManager's unique work status.
        // For now, simple check on our state:
        if (_uiState.value.bodyDisplayState == ContentDisplayState.DOWNLOADING && _uiState.value.message?.id == msgId) {
            Timber.d("ViewModelDBG: Body download already in progress for $msgId.")
            return
        }
        _uiState.update { it.copy(bodyDisplayState = ContentDisplayState.DOWNLOADING, transientError = null) }
        Timber.d("ViewModelDBG: Submitting DownloadMessageBody job for $msgId.")
        syncController.submit(SyncJob.DownloadMessageBody(messageId = msgId, accountId = accId))
    }

    fun onAttachmentClicked(attachment: Attachment) {
        if (attachment.localUri.isNullOrBlank()) {
            // Not downloaded yet, trigger download
            val currentAccountId = accountId
            val currentMessageId = messageId
            if (currentAccountId != null && currentMessageId != null) {
                Timber.d("ViewModelDBG: Manually triggering download for attachment ${attachment.id}")
                val attachmentIdLong = attachment.id.toLongOrNull() ?: -1L
                syncController.submit(
                    SyncJob.DownloadAttachment(
                        accountId = currentAccountId,
                        messageId = currentMessageId,
                        attachmentId = attachmentIdLong
                    )
                )
            }
        }
        // The UI will handle opening the file if localUri is present
    }

    fun retryMessageBodyDownload() {
        val currentAccountId = accountId
        val currentMessageId = messageId
        if (currentAccountId != null && currentMessageId != null) {
            Timber.d("ViewModelDBG: Retrying message body download for $currentMessageId")
            // Set state to DOWNLOADING immediately for responsiveness
            _uiState.update { it.copy(bodyDisplayState = ContentDisplayState.DOWNLOADING, transientError = null) }
            enqueueMessageBodyDownloadInternal(currentMessageId, currentAccountId)
        } else {
            Timber.w("ViewModelDBG: Cannot retry body download, accountId or messageId is null.")
        }
    }

    fun clearTransientError() {
        _uiState.update { it.copy(transientError = null) }
    }
} 