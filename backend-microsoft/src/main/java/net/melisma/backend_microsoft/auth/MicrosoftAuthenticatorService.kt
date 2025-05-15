package net.melisma.backend_microsoft.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class MicrosoftAuthenticatorService : Service() {

    private val TAG = "MicrosoftAuthService"
    private lateinit var authenticator: MicrosoftStubAuthenticator

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: MicrosoftAuthenticatorService created.")
        authenticator = MicrosoftStubAuthenticator(this)
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
        Log.d(TAG, "onDestroy: MicrosoftAuthenticatorService destroyed.")
        super.onDestroy()
    }
} 