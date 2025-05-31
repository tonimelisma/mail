package net.melisma.backend_microsoft.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle
import timber.log.Timber

class MicrosoftStubAuthenticator(private val context: Context) :
    AbstractAccountAuthenticator(context) {

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle? {
        Timber.d("editProperties called for accountType: $accountType - Not supported")
        throw UnsupportedOperationException()
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle? {
        Timber.d("addAccount called for accountType: $accountType - App handles this.")
        return null
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? {
        Timber.d("confirmCredentials called for account: ${account?.name} - Not supported")
        return null
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? {
        Timber.d("getAuthToken called for account: ${account?.name} - Not supported by stub")
        val result = Bundle()
        result.putString(
            android.accounts.AccountManager.KEY_ERROR_MESSAGE,
            "Token acquisition not supported directly by this authenticator."
        )
        return result
    }

    override fun getAuthTokenLabel(authTokenType: String?): String? {
        Timber.d("getAuthTokenLabel called for authTokenType: $authTokenType")
        return authTokenType
    }

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? {
        Timber.d("updateCredentials called for account: ${account?.name} - Not supported")
        return null
    }

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle? {
        Timber.d("hasFeatures called for account: ${account?.name} - Not supported")
        val result = Bundle()
        result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
} 