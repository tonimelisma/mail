package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class ObserveMessagesForFolderUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(account: Account, folder: MailFolder): Flow<MessageDataState> {
        messageRepository.setTargetFolder(account, folder)
        return messageRepository.messageDataState
    }
} 