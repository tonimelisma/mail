package net.melisma.backend_microsoft.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle
import android.util.Log

class MicrosoftStubAuthenticator(private val context: Context) :
    AbstractAccountAuthenticator(context) {

    private val TAG = "MicrosoftStubAuth"

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle? {
        Log.d(TAG, "editProperties called for accountType: $accountType - Not supported")
        throw UnsupportedOperationException()
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle? {
        Log.d(TAG, "addAccount called for accountType: $accountType - App handles this.")
        return null
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? {
        Log.d(TAG, "confirmCredentials called for account: ${account?.name} - Not supported")
        return null
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? {
        Log.d(TAG, "getAuthToken called for account: ${account?.name} - Not supported by stub")
        val result = Bundle()
        result.putString(
            android.accounts.AccountManager.KEY_ERROR_MESSAGE,
            "Token acquisition not supported directly by this authenticator."
        )
        return result
    }

    override fun getAuthTokenLabel(authTokenType: String?): String? {
        Log.d(TAG, "getAuthTokenLabel called for authTokenType: $authTokenType")
        return authTokenType
    }

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? {
        Log.d(TAG, "updateCredentials called for account: ${account?.name} - Not supported")
        return null
    }

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle? {
        Log.d(TAG, "hasFeatures called for account: ${account?.name} - Not supported")
        val result = Bundle()
        result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
} 