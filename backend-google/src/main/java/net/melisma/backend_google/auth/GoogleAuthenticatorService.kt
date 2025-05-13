package net.melisma.backend_google.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Service to host the GoogleStubAuthenticator.
 * This service is declared in the AndroidManifest.xml and is responsible for
 * providing the authenticator to the Android AccountManager framework.
 */
class GoogleAuthenticatorService : Service() {

    private val TAG = "GoogleAuthService"
    private lateinit var authenticator: GoogleStubAuthenticator

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: GoogleAuthenticatorService created.")
        authenticator = GoogleStubAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called with intent action: ${intent?.action}")
        return if (intent?.action == android.accounts.AccountManager.ACTION_AUTHENTICATOR_INTENT) {
            Log.d(TAG, "Returning authenticator binder.")
            authenticator.iBinder
        } else {
            Log.d(TAG, "Binding not for authenticator intent, returning null.")
            null
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: GoogleAuthenticatorService destroyed.")
        super.onDestroy()
    }
}
   
