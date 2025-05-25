package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.repository.ThreadRepository
import javax.inject.Inject

class ObserveThreadsForFolderUseCase @Inject constructor(
    private val threadRepository: ThreadRepository
) {
    suspend operator fun invoke(account: Account, folder: MailFolder): Flow<ThreadDataState> {
        threadRepository.setTargetFolderForThreads(account, folder)
        return threadRepository.threadDataState
    }
} 