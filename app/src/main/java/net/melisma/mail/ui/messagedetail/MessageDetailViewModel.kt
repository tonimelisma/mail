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
import net.melisma.mail.navigation.AppRoutes // Assuming AppRoutes will be created here
import javax.inject.Inject

@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    private val getMessageDetailsUseCase: GetMessageDetailsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<MessageDetailUIState>(MessageDetailUIState.Loading)
    val uiState: StateFlow<MessageDetailUIState> = _uiState.asStateFlow()

    init {
        val accountId: String? = savedStateHandle[AppRoutes.ARG_ACCOUNT_ID]
        val messageId: String? = savedStateHandle[AppRoutes.ARG_MESSAGE_ID]

        if (accountId != null && messageId != null) {
            viewModelScope.launch {
                getMessageDetailsUseCase(messageId, accountId)
                    .onStart { _uiState.value = MessageDetailUIState.Loading }
                    .catch { exception ->
                        _uiState.value = MessageDetailUIState.Error(
                            exception.message ?: "An unknown error occurred"
                        )
                    }
                    .collect { message ->
                        if (message != null) {
                            _uiState.value = MessageDetailUIState.Success(message)
                        } else {
                            _uiState.value = MessageDetailUIState.Error("Message not found")
                        }
                    }
            }
        } else {
            _uiState.value = MessageDetailUIState.Error("Account ID or Message ID is missing")
        }
    }
} 