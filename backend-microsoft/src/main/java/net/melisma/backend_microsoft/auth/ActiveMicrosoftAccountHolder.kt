package net.melisma.backend_microsoft.auth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveMicrosoftAccountHolder @Inject constructor() {
    private val TAG = "ActiveMsAccountHolder"

    private val _activeMicrosoftAccountId = MutableStateFlow<String?>(null)
    val activeMicrosoftAccountId: StateFlow<String?> = _activeMicrosoftAccountId

    init {
        Log.d(TAG, "ActiveMicrosoftAccountHolder: Initialized")
    }

    // This should be called when a Microsoft account becomes active in the app
    fun setActiveMicrosoftAccountId(accountId: String?) {
        Log.d(
            TAG,
            "ActiveMicrosoftAccountHolder: setActiveMicrosoftAccountId called with accountId=${
                accountId?.take(5)
            }..."
        )
        val oldAccountId = _activeMicrosoftAccountId.value
        _activeMicrosoftAccountId.value = accountId

        if (oldAccountId != accountId) {
            if (accountId == null) {
                Log.i(TAG, "ActiveMicrosoftAccountHolder: Active Microsoft account cleared")
            } else {
                Log.i(
                    TAG,
                    "ActiveMicrosoftAccountHolder: Active Microsoft account ID set to: ${
                        accountId.take(5)
                    }..."
                )
            }
        } else {
            Log.d(TAG, "ActiveMicrosoftAccountHolder: Active account unchanged")
        }
        // TODO: Optionally persist to SharedPreferences
    }

    fun getActiveMicrosoftAccountIdValue(): String? {
        val accountId = _activeMicrosoftAccountId.value
        Log.d(
            TAG,
            "ActiveMicrosoftAccountHolder: getActiveMicrosoftAccountIdValue returning: ${
                accountId?.take(5)
            }..."
        )
        return accountId
    }
}