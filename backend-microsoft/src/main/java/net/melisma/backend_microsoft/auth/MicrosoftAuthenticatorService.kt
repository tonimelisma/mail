package net.melisma.backend_microsoft.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

class MicrosoftAuthenticatorService : Service() {

    private lateinit var authenticator: MicrosoftStubAuthenticator

    override fun onCreate() {
        super.onCreate()
        Timber.d("onCreate: MicrosoftAuthenticatorService created.")
        authenticator = MicrosoftStubAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        Timber.d("onBind called with intent action: ${intent?.action}")
        return if (intent?.action == android.accounts.AccountManager.ACTION_AUTHENTICATOR_INTENT) {
            Timber.d("Returning authenticator binder.")
            authenticator.iBinder
        } else {
            Timber.d("Binding not for authenticator intent, returning null.")
            null
        }
    }

    override fun onDestroy() {
        Timber.d("onDestroy: MicrosoftAuthenticatorService destroyed.")
        super.onDestroy()
    }
} 