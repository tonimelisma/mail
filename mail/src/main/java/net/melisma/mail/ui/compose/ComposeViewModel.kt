package net.melisma.mail.ui.compose

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import net.melisma.core_data.model.DraftType
import net.melisma.core_data.model.EmailAddress
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.mail.navigation.AppRoutes
import net.melisma.domain.actions.CreateDraftUseCase
import net.melisma.domain.actions.SaveDraftUseCase
import android.provider.OpenableColumns
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
    private val savedStateHandle: SavedStateHandle,
    private val createDraftUseCase: CreateDraftUseCase,
    private val saveDraftUseCase: SaveDraftUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeScreenState())
    val uiState = _uiState.asStateFlow()

    private val _sendEvent = MutableSharedFlow<Unit>()
    val sendEvent = _sendEvent.asSharedFlow()

    private val autoSaveDelayMs = 3000L // 3-second debounce
    private var autoSaveJob: Job? = null

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
        scheduleAutoSave()
    }

    fun onSubjectChanged(subject: String) {
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(subject = subject)
        )
        scheduleAutoSave()
    }

    fun onBodyChanged(body: String) {
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(body = body)
        )
        scheduleAutoSave()
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

    fun addAttachment(uri: Uri, context: Context) {
        val cr = context.contentResolver
        val cursor = cr.query(uri, null, null, null, null)
        var fileName = "attachment"
        var size: Long = 0
        var mime: String = cr.getType(uri) ?: "application/octet-stream"
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) fileName = it.getString(nameIdx) ?: fileName
                val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0) size = it.getLong(sizeIdx)
            }
        }
        val newAtt = net.melisma.core_data.model.Attachment(
            id = java.util.UUID.randomUUID().toString(),
            messageId = uiState.value.draft.existingMessageId ?: "", // temp
            accountId = "", // filled by repo when saving
            fileName = fileName,
            contentType = mime,
            size = size,
            isInline = false,
            contentId = null,
            localUri = uri.toString(),
            remoteId = null,
            downloadStatus = "DOWNLOADED",
            lastSyncError = null
        )
        _uiState.update { st ->
            st.copy(draft = st.draft.copy(attachments = st.draft.attachments + newAtt))
        }
        scheduleAutoSave()
    }

    fun removeAttachment(att: net.melisma.core_data.model.Attachment) {
        _uiState.update { st ->
            st.copy(draft = st.draft.copy(attachments = st.draft.attachments - att))
        }
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(autoSaveDelayMs)
            autoSave()
        }
    }

    private suspend fun autoSave() {
        val accountId = savedStateHandle.get<String>(AppRoutes.ARG_ACCOUNT_ID) ?: return
        val account = accountRepository.getAccountByIdSuspend(accountId) ?: return
        val currentDraft = uiState.value.draft
        if (currentDraft.existingMessageId == null) {
            // First save creates draft
            val res = createDraftUseCase(account, currentDraft)
            res.getOrNull()?.let { createdMsg ->
                _uiState.update { it.copy(draft = currentDraft.copy(existingMessageId = createdMsg.id)) }
            }
        } else {
            saveDraftUseCase(account, currentDraft.existingMessageId!!, currentDraft)
        }
    }
} 