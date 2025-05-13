package net.melisma.backend_google.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * A minimal stub implementation of AbstractAccountAuthenticator for the custom Google account type.
 * AppAuth handles the actual authentication flow; this class primarily exists to satisfy
 * the Android AccountManager's requirement for an app to manage its own account types.
 */
class GoogleStubAuthenticator(private val context: Context) :
    AbstractAccountAuthenticator(context) {

    private val TAG = "GoogleStubAuthenticator"

    // Editing properties is not supported.
    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle? {
        Log.d(TAG, "editProperties called for accountType: $accountType - Not supported")
        throw UnsupportedOperationException()
    }

    // Don't add accounts through the Android account manager settings.
    // The app's UI flow (via AppAuth) handles account addition.
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle? {
        Log.d(
            TAG,
            "addAccount called for accountType: $accountType - Not launching UI, app handles this."
        )
        // This method might be called if the user tries to add an account
        // of this type from Android Settings.
        // You could optionally launch your app's account setup flow here,
        // but for a simple stub, returning null or an error bundle is okay
        // if your app's primary flow is in-app.
        // For now, returning null as the app handles its own add account UI.
        return null
    }

    // Confirming credentials is not supported.
    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? {
        Log.d(TAG, "confirmCredentials called for account: ${account?.name} - Not supported")
        return null // Or throw UnsupportedOperationException
    }

    // Getting an auth token is not supported directly by this authenticator,
    // as AppAuth and GoogleTokenPersistenceService handle token management.
    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? {
        Log.d(
            TAG,
            "getAuthToken called for account: ${account?.name}, authTokenType: $authTokenType - Not supported by stub"
        )
        // AppAuth flow should be used to get/refresh tokens.
        // This method is for legacy/system-initiated token requests.
        val result = Bundle()
        result.putString(
            android.accounts.AccountManager.KEY_ERROR_MESSAGE,
            "Token acquisition not supported directly by this authenticator."
        )
        return result // Indicate an error or not supported
    }

    // Label for the auth token type.
    override fun getAuthTokenLabel(authTokenType: String?): String? {
        Log.d(TAG, "getAuthTokenLabel called for authTokenType: $authTokenType")
        // You can return a user-friendly name for your token type if needed,
        // though it's often not critical if you don't use different auth token types.
        return authTokenType // Or a custom string like "Access to Gmail"
    }

    // Updating user credentials is not supported.
    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? {
        Log.d(TAG, "updateCredentials called for account: ${account?.name} - Not supported")
        return null // Or throw UnsupportedOperationException
    }

    // Checking features for the account.
    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle? {
        Log.d(TAG, "hasFeatures called for account: ${account?.name} - Not supported")
        // Typically, you'd check if the account supports the requested features.
        // For a stub, returning a bundle indicating false or not supported is okay.
        val result = Bundle()
        result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
}
   
