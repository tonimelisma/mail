package net.melisma.mail.ui.messagedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import net.melisma.domain.data.GetMessageDetailsUseCase
import net.melisma.mail.navigation.AppRoutes
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    private val getMessageDetailsUseCase: GetMessageDetailsUseCase,
    // TODO: P1_SYNC - Inject SyncEngine
    // private val syncEngine: net.melisma.data.sync.SyncEngine,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    // TODO: P1_SYNC - Refine UI states based on sync progress/errors from SyncEngine later.

    private val _uiState = MutableStateFlow<MessageDetailUIState>(MessageDetailUIState.Loading)
    val uiState: StateFlow<MessageDetailUIState> = _uiState.asStateFlow()

    init {
        val accountId: String? = savedStateHandle[AppRoutes.ARG_ACCOUNT_ID]
        val messageId: String? = savedStateHandle[AppRoutes.ARG_MESSAGE_ID]
        Timber.d("ViewModelDBG: MessageDetailViewModel init. AccountId: $accountId, MessageId: $messageId")

        if (accountId != null && messageId != null) {
            viewModelScope.launch {
                Timber.d("ViewModelDBG: Launching coroutine to call getMessageDetailsUseCase for $messageId")
                getMessageDetailsUseCase(messageId, accountId)
                    .onStart {
                        Timber.d("ViewModelDBG: UseCase Flow started for $messageId. Emitting Loading state.")
                        _uiState.value = MessageDetailUIState.Loading
                    }
                    .catch { exception ->
                        Timber.e(
                            exception,
                            "ViewModelDBG: UseCase Flow caught error for $messageId. Emitting Error state."
                        )
                        _uiState.value = MessageDetailUIState.Error(
                            exception.message ?: "An unknown error occurred"
                        )
                    }
                    .collect { message ->
                        if (message != null) {
                            Timber.d("ViewModelDBG: UseCase Flow collected message for $messageId. Subject: '${message.subject}', BodyIsBlank: ${message.body.isNullOrBlank()}, BodyContentType: ${message.bodyContentType}. Emitting Success state.")
                            _uiState.value = MessageDetailUIState.Success(message)
                            if (message.body.isNullOrBlank()) {
                                // TODO: P1_SYNC - Trigger message body download if missing.
                                // Ensure accountId and messageId are available here.
                                Timber.d("ViewModelDBG: Message body is blank for $messageId. Conceptually triggering body download.")
                                // syncEngine.downloadMessageBody(accountId, messageId)
                            }
                        } else {
                            Timber.d("ViewModelDBG: UseCase Flow collected NULL message for $messageId. Emitting Error state - Message not found.")
                            // TODO: P1_SYNC - If message is not found in DB, it might need to be fetched by SyncEngine.
                            // This could be a specific "fetch single message details" worker if not covered by folder sync.
                            // syncEngine.fetchMessageDetails(accountId, messageId)
                            _uiState.value = MessageDetailUIState.Error("Message not found")
                        }
                    }
            }
        } else {
            Timber.w("ViewModelDBG: Account ID or Message ID is missing. AccountId: $accountId, MessageId: $messageId. Emitting Error state.")
            _uiState.value = MessageDetailUIState.Error("Account ID or Message ID is missing")
        }
    }
} 