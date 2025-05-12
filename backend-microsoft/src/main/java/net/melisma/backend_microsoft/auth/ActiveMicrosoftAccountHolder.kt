package net.melisma.backend_microsoft.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveMicrosoftAccountHolder @Inject constructor() {
    private val _activeMicrosoftAccountId = MutableStateFlow<String?>(null)
    val activeMicrosoftAccountId: StateFlow<String?> = _activeMicrosoftAccountId

    // This should be called when a Microsoft account becomes active in the app
    fun setActiveMicrosoftAccountId(accountId: String?) {
        _activeMicrosoftAccountId.value = accountId
        // TODO: Optionally persist to SharedPreferences
    }

    fun getActiveMicrosoftAccountIdValue(): String? = _activeMicrosoftAccountId.value
}