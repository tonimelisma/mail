package net.melisma.backend_google.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * Service to host the GoogleStubAuthenticator.
 * This service is declared in the AndroidManifest.xml and is responsible for
 * providing the authenticator to the Android AccountManager framework.
 */
class GoogleAuthenticatorService : Service() {

    private lateinit var authenticator: GoogleStubAuthenticator

    override fun onCreate() {
        super.onCreate()
        Timber.d("onCreate: GoogleAuthenticatorService created.")
        authenticator = GoogleStubAuthenticator(this)
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
        Timber.d("onDestroy: GoogleAuthenticatorService destroyed.")
        super.onDestroy()
    }
}
