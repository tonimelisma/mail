package net.melisma.backend_google.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class ActiveGoogleAccountHolder @Inject constructor() {

    init {
        Timber.d("ActiveGoogleAccountHolder: Initialized")
    }

    // This should be updated when the user selects an account or after a successful sign-in.
    // For a single Google account app, it can be set after the first successful Google sign-in.
    // Consider loading the last active account ID from SharedPreferences on app start.
    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId

    fun setActiveAccountId(accountId: String?) {
        Timber.d(
            "ActiveGoogleAccountHolder: setActiveAccountId called with accountId=${accountId?.take(5)}..."
        )
        val oldAccountId = _activeAccountId.value
        _activeAccountId.value = accountId

        if (oldAccountId != accountId) {
            if (accountId == null) {
                Timber.i("ActiveGoogleAccountHolder: Active Google account cleared")
            } else {
                Timber.i(
                    "ActiveGoogleAccountHolder: Active Google account ID set to: ${accountId.take(5)}..."
                )
            }
        } else {
            Timber.d("ActiveGoogleAccountHolder: Active account unchanged")
        }
        // TODO: Persist this accountId to SharedPreferences if you want it to
        // survive app restarts as the "last used" Google account.
    }

    fun getActiveAccountIdValue(): String? {
        val accountId = _activeAccountId.value
        Timber.d(
            "ActiveGoogleAccountHolder: getActiveAccountIdValue returning: ${accountId?.take(5)}..."
        )
        return accountId
    }
}