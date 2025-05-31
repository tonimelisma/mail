package net.melisma.backend_microsoft.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class ActiveMicrosoftAccountHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val PREFS_NAME = "ActiveMicrosoftAccountPrefs"
    private val KEY_ACTIVE_ID = "activeMicrosoftAccountId"

    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activeMicrosoftAccountId = MutableStateFlow<String?>(null)
    val activeMicrosoftAccountId: StateFlow<String?> = _activeMicrosoftAccountId

    init {
        val persistedAccountId = sharedPreferences.getString(KEY_ACTIVE_ID, null)
        _activeMicrosoftAccountId.value = persistedAccountId
        Timber.d(
            "ActiveMicrosoftAccountHolder: Initialized. Loaded persisted accountId: ${
                persistedAccountId?.take(
                    5
                )
            }..."
        )
    }

    // This should be called when a Microsoft account becomes active in the app
    fun setActiveMicrosoftAccountId(accountId: String?) {
        Timber.d(
            "ActiveMicrosoftAccountHolder: setActiveMicrosoftAccountId called with accountId=${
                accountId?.take(5)
            }... Old value: ${_activeMicrosoftAccountId.value?.take(5)}..."
        )
        val oldAccountId = _activeMicrosoftAccountId.value
        if (oldAccountId != accountId) {
            _activeMicrosoftAccountId.value = accountId
            with(sharedPreferences.edit()) {
                if (accountId == null) {
                    remove(KEY_ACTIVE_ID)
                    Timber.i(
                        "ActiveMicrosoftAccountHolder: Active Microsoft account cleared and preference removed"
                    )
                } else {
                    putString(KEY_ACTIVE_ID, accountId)
                    Timber.i(
                        "ActiveMicrosoftAccountHolder: Active Microsoft account ID set to: ${
                            accountId.take(5)
                        }... and persisted."
                    )
                }
                apply()
            }
        } else {
            Timber.d(
                "ActiveMicrosoftAccountHolder: Active account ID unchanged, not re-persisting."
            )
        }
    }

    fun getActiveMicrosoftAccountIdValue(): String? {
        val accountId = _activeMicrosoftAccountId.value
        // Timber.d( // This log can be noisy, consider removing or making it trace level for regular use
        //     "ActiveMicrosoftAccountHolder: getActiveMicrosoftAccountIdValue returning: ${
        //         accountId?.take(5)
        //     }..."
        // )
        return accountId
    }
}