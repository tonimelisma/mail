package net.melisma.domain.actions

interface SyncAccountUseCase {
    suspend operator fun invoke(accountId: String): Result<Unit>
} 