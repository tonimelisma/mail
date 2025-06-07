package net.melisma.mail.ui.messagedetail

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.Message
import net.melisma.core_data.preferences.DownloadPreference
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.data.sync.SyncEngine
import net.melisma.data.sync.workers.AttachmentDownloadWorker
import net.melisma.data.sync.workers.MessageBodyDownloadWorker
import net.melisma.domain.data.GetMessageDetailsUseCase
import net.melisma.mail.navigation.AppRoutes
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose

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
    val attachmentDisplayStates: Map<String, ContentDisplayState> = emptyMap(),
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
    private val workManager: WorkManager,
    private val syncEngine: SyncEngine,
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
            getMessageDetailsUseCase(messageId, accountId)
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
                .collect { message ->
                    if (message != null) {
                        Timber.d("ViewModelDBG: UseCase Flow collected message for $messageId. Subject: '${message.subject}'. RemoteId: ${message.remoteId}, LastSync: ${message.lastSuccessfulSyncTimestamp}")
                        _uiState.update { currentState ->
                            currentState.copy(
                                messageOverallState = MessageDetailUIState.Success(message)
                            )
                        }
                        val currentUiState = _uiState.value
                        evaluateAndTriggerDownloads(
                            message,
                            currentUiState.isOnline,
                            currentUiState.isWifiConnected
                        )

                        // Auto-refresh logic
                        if (currentUiState.isOnline && this@MessageDetailViewModel.accountId != null && !message.remoteId.isNullOrBlank()) {
                            val lastSync = message.lastSuccessfulSyncTimestamp
                            val staleThresholdMs = 5 * 60 * 1000L // 5 minutes
                            val isStale = lastSync == null || (System.currentTimeMillis() - lastSync) > staleThresholdMs

                            if (isStale) {
                                Timber.d("ViewModelDBG: Message ${message.remoteId} (local: ${message.id}) is stale (last sync: $lastSync). Triggering silent refresh.")
                                syncEngine.refreshMessage(this@MessageDetailViewModel.accountId!!, message.id, message.remoteId)
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
        isWifi: Boolean // isWifi is now less relevant for immediate body download if online at all
    ) {
        var finalBodyState: ContentDisplayState
        val newAttachmentStates = _uiState.value.attachmentDisplayStates.toMutableMap()
        val bodyPref = _uiState.value.bodyDownloadPreference // Still needed for logging/potential future logic
        val attachmentPref = _uiState.value.attachmentDownloadPreference

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
                // Check for violation of UX rule
                if (bodyPref == DownloadPreference.ON_WIFI && !isWifi) {
                    Timber.e("ViewModelDBG_DIRE_ERROR: Body download for ${message.id} initiated on non-WiFi, but preference was ON_WIFI. This is an override for active view. Logging for awareness.")
                }
            } else { // Device is OFFLINE
                finalBodyState = ContentDisplayState.NOT_DOWNLOADED_OFFLINE
            }
        }

        // Evaluate Attachments (retains existing preference-based logic for attachments for now, can be aligned if needed)
        message.attachments.forEach { att ->
            if (!att.localUri.isNullOrBlank()) {
                newAttachmentStates[att.id] = ContentDisplayState.DOWNLOADED
            } else { // Attachment not downloaded
                if (isOnline) {
                    if (attachmentPref == DownloadPreference.ALWAYS ||
                        (attachmentPref == DownloadPreference.ON_WIFI && isWifi) || // Strict ON_WIFI for attachments background
                        attachmentPref == DownloadPreference.ON_DEMAND // Active view implies demand
                    ) {
                        // For attachments, if ON_WIFI, only download if on Wi-Fi or if Always/OnDemand
                        // This differs from body's immediate download on any online connection for active view.
                        // If an attachment also needs immediate download on any connection for active view, this logic needs adjustment.
                        newAttachmentStates[att.id] = ContentDisplayState.DOWNLOADING
                        val existingState = _uiState.value.attachmentDisplayStates[att.id]
                        if (existingState != ContentDisplayState.DOWNLOADING) {
                             enqueueAttachmentDownloadInternal(message.id, accountId!!, att)
                        } else {
                            Timber.d("ViewModelDBG: Attachment ${att.id} download already marked as DOWNLOADING. Skipping re-enqueue.")
                        }
                    } else if (attachmentPref == DownloadPreference.ON_WIFI && !isWifi) {
                        newAttachmentStates[att.id] = ContentDisplayState.NOT_DOWNLOADED_WILL_DOWNLOAD_ON_WIFI
                    } else {
                        // If online but doesn't meet Always, or (OnWifi and isWifi), or OnDemand, it might be waiting for connection type or explicit user action.
                        // This state assumes if not downloading now, it will happen when conditions meet (e.g. Wi-Fi)
                        newAttachmentStates[att.id] = ContentDisplayState.NOT_DOWNLOADED_WILL_DOWNLOAD_WHEN_ONLINE
                        Timber.d("ViewModelDBG: Attachment ${att.id} for ${message.id} not downloading. Pref: $attachmentPref, Online: $isOnline, Wifi: $isWifi")
                    }
                } else { // Device is OFFLINE
                    newAttachmentStates[att.id] = ContentDisplayState.NOT_DOWNLOADED_OFFLINE
                }
            }
        }
        _uiState.update { it.copy(bodyDisplayState = finalBodyState, attachmentDisplayStates = newAttachmentStates) }
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
        Timber.d("ViewModelDBG: Enqueuing MessageBodyDownloadWorker for $msgId.")
        val workRequest = OneTimeWorkRequestBuilder<MessageBodyDownloadWorker>()
            .setInputData(
                workDataOf(
                    MessageBodyDownloadWorker.KEY_ACCOUNT_ID to accId,
                    MessageBodyDownloadWorker.KEY_MESSAGE_ID to msgId
                )
            )
            .build()
        val workName = "message-body-download-$msgId"
        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP, // KEEPs if already running, good.
            workRequest
        )
        observeWorkStatus(workRequest.id, msgId, null)
    }

    private fun enqueueAttachmentDownloadInternal(msgId: String, accId: String, attachment: Attachment) {
        if (_uiState.value.attachmentDisplayStates[attachment.id] == ContentDisplayState.DOWNLOADING) {
             Timber.d("ViewModelDBG: Attachment download already in progress for ${attachment.id}.")
            return
        }
        _uiState.update { currentState ->
            val newStates = currentState.attachmentDisplayStates.toMutableMap()
            newStates[attachment.id] = ContentDisplayState.DOWNLOADING
            currentState.copy(attachmentDisplayStates = newStates, transientError = null)
        }

        Timber.d("ViewModelDBG: Enqueuing AttachmentDownloadWorker for attachment ${attachment.id} in message $msgId.")
        val workRequest = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
            .setInputData(
                workDataOf(
                    AttachmentDownloadWorker.KEY_ACCOUNT_ID to accId,
                    AttachmentDownloadWorker.KEY_MESSAGE_ID to msgId,
                    AttachmentDownloadWorker.KEY_ATTACHMENT_ID to attachment.id,
                    AttachmentDownloadWorker.KEY_ATTACHMENT_NAME to attachment.fileName // Ensure these keys are correct
                )
            )
            .build()
        val workName = "attachment-download-${attachment.id}"
        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        observeWorkStatus(workRequest.id, msgId, attachment.id)
    }

    private fun observeWorkStatus(workId: UUID, messageIdParam: String, attachmentId: String?) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo != null) {
                    Timber.d("ViewModelDBG: WorkInfo for ${if (attachmentId == null) "body" else "attachment $attachmentId"} of $messageIdParam: ${workInfo.state}")
                    val currentViewModelAccountId = this@MessageDetailViewModel.accountId
                    val currentViewModelMessageId = this@MessageDetailViewModel.messageId

                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            if (currentViewModelAccountId != null && currentViewModelMessageId == messageIdParam) {
                                // Reload message details to get the freshly downloaded body/attachment info from DB
                                // This will re-trigger evaluateAndTriggerDownloads and auto-refresh logic if needed.
                                loadMessageDetails(currentViewModelAccountId, messageIdParam)
                                Timber.d("ViewModelDBG: Work for $messageIdParam (att: $attachmentId) succeeded. Reloading message details.")
                            } else {
                                Timber.d("ViewModelDBG: Work for $messageIdParam (att: $attachmentId) succeeded, but view is for $currentViewModelMessageId. No immediate UI reload.")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMsg = workInfo.outputData.getString(if (attachmentId == null) MessageBodyDownloadWorker.KEY_RESULT_ERROR else AttachmentDownloadWorker.KEY_RESULT_ERROR) ?: "Download failed"
                            Timber.w("ViewModelDBG: Work FAILED for $messageIdParam (att: $attachmentId) - $errorMsg")
                            if (currentViewModelMessageId == messageIdParam) { // Only update UI if it's for the current message
                                if (attachmentId == null) {
                                    _uiState.update { it.copy(bodyDisplayState = ContentDisplayState.ERROR, transientError = errorMsg) }
                                } else {
                                    _uiState.update { currentState ->
                                        val newStates = currentState.attachmentDisplayStates.toMutableMap()
                                        newStates[attachmentId] = ContentDisplayState.ERROR
                                        currentState.copy(attachmentDisplayStates = newStates, transientError = errorMsg)
                                    }
                                }
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            val errorMsg = "Download cancelled"
                            Timber.w("ViewModelDBG: Work CANCELLED for $messageIdParam (att: $attachmentId)")
                            if (currentViewModelMessageId == messageIdParam) { // Only update UI if it's for the current message
                                if (attachmentId == null) {
                                    _uiState.update { it.copy(bodyDisplayState = ContentDisplayState.ERROR, transientError = errorMsg) }
                                } else {
                                     _uiState.update { currentState ->
                                        val newStates = currentState.attachmentDisplayStates.toMutableMap()
                                        newStates[attachmentId] = ContentDisplayState.ERROR
                                        currentState.copy(attachmentDisplayStates = newStates, transientError = errorMsg)
                                    }
                                }
                            }
                        }
                        else -> { /* RUNNING, ENQUEUED, BLOCKED - state already set to LOADING by enqueue method */ }
                    }
                }
            }
        }
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

    fun retryAttachmentDownload(attachmentIdToRetry: String) {
        val currentAccountId = accountId
        val currentMessageId = messageId
        val currentMessage = (_uiState.value.messageOverallState as? MessageDetailUIState.Success)?.message

        if (currentAccountId != null && currentMessageId != null && currentMessage != null) {
            currentMessage.attachments.find { it.id == attachmentIdToRetry }?.let { attachmentToRetry ->
                Timber.d("ViewModelDBG: Retrying attachment download for ${attachmentToRetry.id} in message $currentMessageId")
                // Set state to DOWNLOADING immediately for responsiveness
                _uiState.update { currentState ->
                    val newStates = currentState.attachmentDisplayStates.toMutableMap()
                    newStates[attachmentToRetry.id] = ContentDisplayState.DOWNLOADING
                    currentState.copy(attachmentDisplayStates = newStates, transientError = null)
                }
                enqueueAttachmentDownloadInternal(currentMessageId, currentAccountId, attachmentToRetry)
            } ?: run {
                Timber.w("ViewModelDBG: Attachment with id $attachmentIdToRetry not found in current message for retry.")
            }
        } else {
            Timber.w("ViewModelDBG: Cannot retry attachment download, missing account/message info or attachment not found.")
        }
    }

    fun clearTransientError() {
        _uiState.update { it.copy(transientError = null) }
    }
} 