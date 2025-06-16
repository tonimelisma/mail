package net.melisma.mail.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import net.melisma.core_data.model.DraftType
import net.melisma.core_data.model.EmailAddress
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.mail.navigation.AppRoutes
import javax.inject.Inject

data class ComposeScreenState(
    val draft: MessageDraft = MessageDraft(),
    val isSending: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val accountRepository: AccountRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeScreenState())
    val uiState = _uiState.asStateFlow()

    private val _sendEvent = MutableSharedFlow<Unit>()
    val sendEvent = _sendEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            val accountId = savedStateHandle.get<String>(AppRoutes.ARG_ACCOUNT_ID) ?: return@launch
            val action = savedStateHandle.get<String>(AppRoutes.ARG_ACTION) ?: "new"
            val messageId = savedStateHandle.get<String>(AppRoutes.ARG_MESSAGE_ID)

            // Load signature first
            val signature = userPreferencesRepository.userPreferencesFlow.first().signature

            var draft = MessageDraft(type = DraftType.NEW)

            when (action.lowercase()) {
                "reply", "reply_all", "forward" -> {
                    if (messageId != null) {
                        val origMsg = messageRepository.getMessageDetails(messageId, accountId).filterNotNull().first()
                        draft = when (action.lowercase()) {
                            "reply" -> buildReplyDraft(origMsg)
                            "reply_all" -> buildReplyAllDraft(origMsg)
                            "forward" -> buildForwardDraft(origMsg)
                            else -> draft
                        }
                    }
                }
                "new" -> {
                    // keep default
                }
            }

            if (signature.isNotBlank()) {
                // Avoid double-adding signature
                val bodyWithSig = if (draft.body.contains(signature)) draft.body else draft.body + "\n\n" + signature
                draft = draft.copy(body = bodyWithSig)
            }

            _uiState.update { it.copy(draft = draft) }
        }
    }

    private fun buildReplyDraft(original: Message): MessageDraft {
        val to = listOfNotNull(original.senderAddress?.let { EmailAddress(it) })
        val quoted = "\n\n> " + (original.body ?: original.bodyPreview ?: "")
        return MessageDraft(
            type = DraftType.REPLY,
            subject = prependIfMissing("Re: ", original.subject ?: ""),
            to = to,
            existingMessageId = null,
            body = quoted
        )
    }

    private fun buildReplyAllDraft(original: Message): MessageDraft {
        val toAddresses = (original.recipientAddresses ?: emptyList()) + listOfNotNull(original.senderAddress)
        val unique = toAddresses.distinct().map { EmailAddress(it) }
        val quoted = "\n\n> " + (original.body ?: original.bodyPreview ?: "")
        return MessageDraft(
            type = DraftType.REPLY_ALL,
            subject = prependIfMissing("Re: ", original.subject ?: ""),
            to = unique,
            body = quoted
        )
    }

    private fun buildForwardDraft(original: Message): MessageDraft {
        val quoted = "\n\n---------- Forwarded message ----------\n" + (original.body
            ?: original.bodyPreview ?: "")
        return MessageDraft(
            type = DraftType.FORWARD,
            subject = prependIfMissing("Fwd: ", original.subject ?: ""),
            body = quoted
        )
    }

    private fun prependIfMissing(prefix: String, subject: String): String =
        if (subject.startsWith(prefix, ignoreCase = true)) subject else prefix + subject

    fun onToChanged(to: String) {
        val addresses = to.split(",").map { EmailAddress(it.trim()) }
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(to = addresses)
        )
    }

    fun onSubjectChanged(subject: String) {
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(subject = subject)
        )
    }

    fun onBodyChanged(body: String) {
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(body = body)
        )
    }

    fun send() {
        viewModelScope.launch {
            val accountId = savedStateHandle.get<String>(AppRoutes.ARG_ACCOUNT_ID)!!
            val account = accountRepository.getAccountByIdSuspend(accountId) ?: return@launch

            _uiState.update { it.copy(isSending = true) }
            try {
                messageRepository.sendMessage(account, uiState.value.draft)
                _sendEvent.emit(Unit)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }
} 