package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.ThreadDataState
import javax.inject.Inject

class GetThreadDetailsUseCase @Inject constructor() {
    operator fun invoke(threadId: String, currentState: ThreadDataState): Flow<Result<MailThread>> =
        flow {
            when (currentState) {
                is ThreadDataState.Success -> {
                    val thread = currentState.threads.find { it.id == threadId }
                    if (thread != null) {
                        emit(Result.success(thread))
                    } else {
                        emit(Result.failure(NoSuchElementException("Thread with ID $threadId not found in current success state.")))
                    }
                }

                is ThreadDataState.Error -> {
                    emit(Result.failure(IllegalStateException("Cannot get thread details, current state is Error: ${currentState.error}")))
                }

                ThreadDataState.Initial, ThreadDataState.Loading -> {
                    // Emit a failure or a specific state indicating data is not ready
                    emit(Result.failure(IllegalStateException("Thread data is not loaded yet (Initial or Loading state).")))
                }
            }
        }
} 