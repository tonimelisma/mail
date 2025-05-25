package net.melisma.domain.account

import android.app.Activity // Import Activity
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject

class SignInUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) {
    suspend operator fun invoke(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> {
        return accountRepository.signIn(activity, loginHint, providerType)
    }
} 