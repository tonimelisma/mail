package net.melisma.mail.ui.threaddetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Message
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.domain.data.GetThreadDetailsUseCase
import net.melisma.mail.navigation.AppRoutes
import javax.inject.Inject

@HiltViewModel
class ThreadDetailViewModel @Inject constructor(
    private val getThreadDetailsUseCase: GetThreadDetailsUseCase,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository, // For fetching individual message bodies
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<ThreadDetailUIState>(ThreadDetailUIState.Loading)
    val uiState: StateFlow<ThreadDetailUIState> = _uiState.asStateFlow()

    private val accountId: String? = savedStateHandle[AppRoutes.ARG_ACCOUNT_ID]
    private val threadId: String? = savedStateHandle[AppRoutes.ARG_THREAD_ID]

    private val TAG = "ThreadDetailVM"

    init {
        if (accountId == null || threadId == null) {
            _uiState.value = ThreadDetailUIState.Error("Account ID or Thread ID is missing")
        } else {
            viewModelScope.launch {
                threadRepository.threadDataState.collectLatest { currentThreadDataState ->
                    getThreadDetailsUseCase(threadId, currentThreadDataState)
                        .collectLatest { result ->
                            result.fold(
                                onSuccess = { mailThread ->
                                    _uiState.value = ThreadDetailUIState.Success(
                                        threadMessages = mailThread.messages.map {
                                            ThreadMessageItem(
                                                it
                                            )
                                        },
                                        threadSubject = mailThread.subject,
                                        accountId = accountId,
                                        threadId = threadId
                                    )
                                    // Optionally, trigger initial load for the first few message bodies here
                                    // For example, load the body for the first unread message or the latest message.
                                },
                                onFailure = { exception ->
                                    Log.w(
                                        TAG,
                                        "Error getting thread details: ${exception.message}",
                                        exception
                                    )
                                    _uiState.value = ThreadDetailUIState.Error(
                                        exception.message ?: "Error fetching thread details"
                                    )
                                }
                            )
                        }
                }
            }
        }
    }

    fun requestMessageBody(messageIdToLoad: String) {
        if (accountId == null) {
            Log.e(
                TAG,
                "requestMessageBody: AccountId is null, cannot fetch body for $messageIdToLoad"
            )
            return
        }

        val currentState = _uiState.value
        if (currentState !is ThreadDetailUIState.Success) {
            Log.w(
                TAG,
                "requestMessageBody: Current state is not Success, cannot update body for $messageIdToLoad"
            )
            return
        }

        val messageIndex =
            currentState.threadMessages.indexOfFirst { it.message.id == messageIdToLoad }
        if (messageIndex == -1) {
            Log.w(
                TAG,
                "requestMessageBody: Message $messageIdToLoad not found in current threadMessages."
            )
            return
        }

        // Update state to Loading for this specific message
        _uiState.update {
            if (it is ThreadDetailUIState.Success) {
                it.copy(threadMessages = it.threadMessages.mapIndexed { index, item ->
                    if (index == messageIndex) item.copy(bodyState = BodyLoadingState.Loading) else item
                })
            } else it
        }

        viewModelScope.launch {
            Log.d(TAG, "Fetching body for message: $messageIdToLoad in account $accountId")
            messageRepository.getMessageDetails(messageIdToLoad, accountId)
                .catch { e ->
                    Log.e(TAG, "Error fetching message body for $messageIdToLoad: ${e.message}", e)
                    _uiState.update {
                        if (it is ThreadDetailUIState.Success) {
                            it.copy(threadMessages = it.threadMessages.mapIndexed { index, item ->
                                if (index == messageIndex) item.copy(
                                    bodyState = BodyLoadingState.Error(
                                        e.message ?: "Unknown error"
                                    )
                                ) else item
                            })
                        } else it
                    }
                }
                .collectLatest { messageWithBody: Message? ->
                    if (messageWithBody?.body != null) {
                        Log.d(
                            TAG,
                            "Successfully fetched body for message: $messageIdToLoad. Body not null."
                        )
                        _uiState.update {
                            if (it is ThreadDetailUIState.Success) {
                                it.copy(threadMessages = it.threadMessages.mapIndexed { index, item ->
                                    if (index == messageIndex) item.copy(
                                        bodyState = BodyLoadingState.Loaded(
                                            messageWithBody.body ?: ""
                                        )
                                    ) else item
                                })
                            } else it
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Fetched message $messageIdToLoad, but body is still null or empty."
                        )
                        _uiState.update {
                            if (it is ThreadDetailUIState.Success) {
                                it.copy(threadMessages = it.threadMessages.mapIndexed { index, item ->
                                    if (index == messageIndex) item.copy(
                                        bodyState = BodyLoadingState.Error(
                                            "Body content was empty or null after fetch."
                                        )
                                    ) else item
                                })
                            } else it
                        }
                    }
                }
        }
    }
} 