package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.backend_google.model.ManagedGoogleAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject
import javax.inject.Singleton

// Sealed result classes for GoogleAuthManager public API
sealed class GoogleSignInResult {
    data class Success(val managedAccount: ManagedGoogleAccount, val authState: AuthState) :
        GoogleSignInResult()

    data class Error(
        val errorMessage: String,
        val exception: Throwable? = null,
        val persistenceFailure: PersistenceResult.Failure<GooglePersistenceErrorType>? = null
    ) : GoogleSignInResult()

    object Cancelled : GoogleSignInResult()
    // UiActionRequired was removed as signInInteractive now directly returns Intent or throws
}

sealed class GoogleSignOutResult {
    object Success : GoogleSignOutResult()
    data class Error(
        val errorMessage: String,
        val exception: Throwable? = null,
        val persistenceFailure: PersistenceResult.Failure<GooglePersistenceErrorType>? = null
    ) : GoogleSignOutResult()
}

sealed class GoogleGetTokenResult {
    data class Success(val accessToken: String) : GoogleGetTokenResult()
    data class Error(
        val errorMessage: String,
        val errorType: GooglePersistenceErrorType? = null,
        val exception: Throwable? = null
    ) : GoogleGetTokenResult()

    data class NeedsReauthentication(val accountId: String) :
        GoogleGetTokenResult() // For cases like invalid_grant
}

@Singleton
class GoogleAuthManager @Inject constructor(
    private val appAuthHelperService: AppAuthHelperService,
    private val tokenPersistenceService: GoogleTokenPersistenceService,
    private val activeGoogleAccountHolder: ActiveGoogleAccountHolder,
    @ApplicationScope private val externalScope: CoroutineScope,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "GoogleAuthManager"

    // Step 2.3: Helper Functions (Private)
    private fun mapPersistenceErrorToSignInError(
        failure: PersistenceResult.Failure<GooglePersistenceErrorType>,
        contextMessage: String
    ): GoogleSignInResult.Error {
        Log.e(
            TAG,
            "$contextMessage: Persistence Error: ${failure.errorType}, Msg: ${failure.message}",
            failure.cause
        )
        return GoogleSignInResult.Error(
            errorMessage = "$contextMessage: ${failure.message ?: failure.errorType.name}",
            exception = failure.cause,
            persistenceFailure = failure
        )
    }

    private fun mapPersistenceErrorToSignOutError(
        failure: PersistenceResult.Failure<GooglePersistenceErrorType>,
        contextMessage: String
    ): GoogleSignOutResult.Error {
        Log.e(
            TAG,
            "$contextMessage: Persistence Error: ${failure.errorType}, Msg: ${failure.message}",
            failure.cause
        )
        return GoogleSignOutResult.Error(
            errorMessage = "$contextMessage: ${failure.message ?: failure.errorType.name}",
            exception = failure.cause,
            persistenceFailure = failure
        )
    }

    private fun mapPersistenceErrorToGetTokenError(
        failure: PersistenceResult.Failure<GooglePersistenceErrorType>,
        contextMessage: String
    ): GoogleGetTokenResult.Error {
        Log.e(
            TAG,
            "$contextMessage: Persistence Error: ${failure.errorType}, Msg: ${failure.message}",
            failure.cause
        )
        return GoogleGetTokenResult.Error(
            errorMessage = "$contextMessage: ${failure.message ?: failure.errorType.name}",
            errorType = failure.errorType,
            exception = failure.cause
        )
    }

    // Step 2.4: Implement GoogleAuthManager.signInInteractive(activity: Activity, loginHint: String?): Intent (Suspend Function)
    suspend fun signInInteractive(activity: Activity, loginHint: String?): Intent {
        return withContext(ioDispatcher) {
            try {
                val authRequest = appAuthHelperService.createAuthorizationRequest(
                    loginHint = loginHint,
                    scopes = AppAuthHelperService.MANDATORY_SCOPES + AppAuthHelperService.GMAIL_SCOPES // Assuming these are defined in AppAuthHelperService
                )
                Log.d(TAG, "Creating sign-in intent with request: $authRequest")
                appAuthHelperService.getAuthorizationIntent(authRequest)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create sign-in intent", e)
                throw IllegalStateException("Failed to create sign-in intent: ${e.message}", e)
            }
        }
    }

    // Step 2.5: Implement GoogleAuthManager.handleAuthorizationResponse(authResponse: AuthorizationResponse?, authException: AuthorizationException?): Flow<GoogleSignInResult>
    fun handleAuthorizationResponse(
        authResponse: AuthorizationResponse?,
        authException: AuthorizationException?
    ): Flow<GoogleSignInResult> = flow {
        if (authException != null) {
            Log.w(TAG, "Authorization failed with exception", authException)
            if (authException.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) {
                emit(GoogleSignInResult.Cancelled)
            } else {
                emit(
                    GoogleSignInResult.Error(
                        "Authorization failed: ${authException.message}",
                        authException
                    )
                )
            }
            return@flow
        }

        if (authResponse == null) {
            Log.e(TAG, "Authorization response is null without exception")
            emit(GoogleSignInResult.Error("Authorization failed: No response received."))
            return@flow
        }

        Log.d(TAG, "Handling authorization response: $authResponse")
        val tokenExchangeResult = try {
            appAuthHelperService.exchangeAuthorizationCode(authResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            emit(GoogleSignInResult.Error("Token exchange failed: ${e.message}", e))
            return@flow
        }

        if (tokenExchangeResult == null) {
            Log.e(TAG, "Token exchange result is null")
            emit(GoogleSignInResult.Error("Token exchange failed: No token response received."))
            return@flow
        }

        val (tokenResponse, exchangeException) = tokenExchangeResult
        if (exchangeException != null) {
            Log.e(TAG, "Token exchange failed with exception", exchangeException)
            emit(
                GoogleSignInResult.Error(
                    "Token exchange failed: ${exchangeException.message}",
                    exchangeException
                )
            )
            return@flow
        }

        if (tokenResponse == null) {
            Log.e(TAG, "Token response is null after exchange")
            emit(GoogleSignInResult.Error("Token exchange failed: TokenResponse is null."))
            return@flow
        }

        Log.d(
            TAG,
            "Token exchange successful. ID Token: ${tokenResponse.idToken}, Access Token: ${tokenResponse.accessToken}"
        )

        val userInfo = try {
            tokenResponse.idToken?.let { appAuthHelperService.parseIdToken(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ID token", e)
            emit(GoogleSignInResult.Error("Failed to parse ID token: ${e.message}", e))
            return@flow
        }

        if (userInfo == null) {
            Log.e(TAG, "User info is null after parsing ID token")
            emit(GoogleSignInResult.Error("Failed to retrieve user info from ID token."))
            return@flow
        }

        Log.d(TAG, "User info parsed: $userInfo")

        val persistenceOpResult = tokenPersistenceService.saveTokens(userInfo, tokenResponse)
        when (persistenceOpResult) {
            is PersistenceResult.Success -> {
                val accountId = userInfo.id
                Log.d(TAG, "Tokens and user info saved successfully for account $accountId")
                activeGoogleAccountHolder.setActiveAccount(accountId)
                val managedAccount = ManagedGoogleAccount(
                    accountId = accountId,
                    email = userInfo.email,
                    displayName = userInfo.displayName,
                    photoUrl = userInfo.photoUrl
                )
                val currentAuthState = AuthState(tokenResponse, null) // No AuthorizationException
                emit(GoogleSignInResult.Success(managedAccount, currentAuthState))
            }

            is PersistenceResult.Failure -> {
                emit(mapPersistenceErrorToSignInError(persistenceOpResult, "Failed to save tokens"))
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in handleAuthorizationResponse flow", e)
        emit(GoogleSignInResult.Error("An unexpected error occurred: ${e.message}", e))
    }

    // Step 2.6: Implement GoogleAuthManager.getAccount(accountId: String): Flow<ManagedGoogleAccount?>
    fun getAccount(accountId: String): Flow<ManagedGoogleAccount?> = flow {
        Log.d(TAG, "Getting account for ID: $accountId")
        when (val persistenceResult = tokenPersistenceService.getUserInfo(accountId)) {
            is PersistenceResult.Success -> {
                val userInfo = persistenceResult.data
                if (userInfo != null) {
                    emit(
                        ManagedGoogleAccount(
                            accountId = userInfo.id,
                            email = userInfo.email,
                            displayName = userInfo.displayName,
                            photoUrl = userInfo.photoUrl
                        )
                    )
                } else {
                    emit(null) // UserInfo not found
                }
            }

            is PersistenceResult.Failure -> {
                Log.e(
                    TAG,
                    "Failed to get account $accountId: ${persistenceResult.errorType} - ${persistenceResult.message}",
                    persistenceResult.cause
                )
                emit(null) // Or handle error more specifically if needed by consumers
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in getAccount flow for $accountId", e)
        emit(null) // Emit null on unexpected errors
    }

    // Step 2.7: Implement GoogleAuthManager.getAccounts(): Flow<List<ManagedGoogleAccount>>
    fun getAccounts(): Flow<List<ManagedGoogleAccount>> = flow {
        Log.d(TAG, "Getting all persisted Google accounts")
        when (val persistenceResult = tokenPersistenceService.getAllGoogleUserInfos()) {
            is PersistenceResult.Success -> {
                val managedAccounts = persistenceResult.data.map { userInfo ->
                    ManagedGoogleAccount(
                        accountId = userInfo.id,
                        email = userInfo.email,
                        displayName = userInfo.displayName,
                        photoUrl = userInfo.photoUrl
                    )
                }
                emit(managedAccounts)
            }

            is PersistenceResult.Failure -> {
                Log.e(
                    TAG,
                    "Failed to get all accounts: ${persistenceResult.errorType} - ${persistenceResult.message}",
                    persistenceResult.cause
                )
                emit(emptyList()) // Emit empty list on failure
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in getAccounts flow", e)
        emit(emptyList()) // Emit empty list on unexpected errors
    }

    // Step 2.8: Implement GoogleAuthManager.signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult>
    fun signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult> = flow {
        val accountId = managedAccount.accountId
        Log.d(TAG, "Signing out account: $accountId")

        // 1. Attempt to get AuthState to retrieve refreshToken.
        val authStateResult = tokenPersistenceService.getAuthState(accountId)
        var refreshToken: String? = null
        if (authStateResult is PersistenceResult.Success && authStateResult.data != null) {
            refreshToken = authStateResult.data.refreshToken
            Log.d(TAG, "Retrieved refreshToken for $accountId: ${refreshToken != null}")
        } else if (authStateResult is PersistenceResult.Failure) {
            Log.w(
                TAG,
                "Failed to get AuthState for $accountId during sign out: ${authStateResult.errorType}. Proceeding with token clearing."
            )
        }

        // 2. If refreshToken exists, call appAuthHelperService.revokeToken(refreshToken).
        if (refreshToken != null) {
            try {
                val revocationSuccess = appAuthHelperService.revokeToken(refreshToken)
                if (revocationSuccess) {
                    Log.i(TAG, "Token revoked successfully for account $accountId.")
                } else {
                    Log.w(
                        TAG,
                        "Token revocation failed or was not applicable for account $accountId."
                    )
                    // Not treating this as a fatal error for sign-out, but logging it.
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Exception during token revocation for $accountId. Proceeding with local sign out.",
                    e
                )
                // Not treating this as a fatal error for sign-out
            }
        } else {
            Log.d(TAG, "No refresh token found for $accountId, skipping revocation.")
        }

        // 3. Call tokenPersistenceService.clearTokens(managedAccount.accountId, removeAccountFromManagerFlag = true).
        when (val clearResult =
            tokenPersistenceService.clearTokens(accountId, removeAccountFromManagerFlag = true)) {
            is PersistenceResult.Success -> {
                Log.i(TAG, "Successfully cleared tokens and account data for $accountId")
                // 4. If clearing is successful, update activeGoogleAccountHolder if the signed-out account was active.
                if (activeGoogleAccountHolder.activeAccountId.value == accountId) {
                    activeGoogleAccountHolder.clearActiveAccount()
                    Log.d(
                        TAG,
                        "Cleared active Google account as it was the one signed out: $accountId"
                    )
                }
                emit(GoogleSignOutResult.Success)
            }

            is PersistenceResult.Failure -> {
                emit(
                    mapPersistenceErrorToSignOutError(
                        clearResult,
                        "Failed to clear tokens/account data"
                    )
                )
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in signOut flow for $accountId", e)
        emit(
            GoogleSignOutResult.Error(
                "An unexpected error occurred during sign out: ${e.message}",
                e
            )
        )
    }

    // Step 2.9: Implement GoogleAuthManager.getFreshAccessToken(accountId: String): GoogleGetTokenResult (Suspend Function)
    suspend fun getFreshAccessToken(accountId: String): GoogleGetTokenResult =
        withContext(ioDispatcher) {
            Log.d(TAG, "Attempting to get fresh access token for account: $accountId")

            // 1. Retrieve AuthState using tokenPersistenceService.getAuthState(accountId).
            val authStateResult = tokenPersistenceService.getAuthState(accountId)
            val currentAuthState: AuthState = when (authStateResult) {
                is PersistenceResult.Success -> authStateResult.data ?: run {
                    Log.e(TAG, "No AuthState found for account $accountId.")
                    return@withContext GoogleGetTokenResult.Error(
                        "No authentication state found for account.",
                        GooglePersistenceErrorType.ACCOUNT_NOT_FOUND
                    )
                }

                is PersistenceResult.Failure -> {
                    Log.e(
                        TAG,
                        "Failed to get AuthState for $accountId: ${authStateResult.errorType}",
                        authStateResult.cause
                    )
                    return@withContext mapPersistenceErrorToGetTokenError(
                        authStateResult,
                        "Failed to retrieve AuthState"
                    )
                }
            }

            // 2. If AuthState needs refresh (or access token is null).
            // AppAuth typically refreshes if token is expired or within a threshold.
            // We can force a refresh if needed or rely on performActionWithFreshTokens.
            // For simplicity, let's check if access token exists and assume AppAuth handles expiry.
            // A more robust check would involve currentAuthState.needsTokenRefresh based on expiry time.
            if (currentAuthState.accessToken == null || currentAuthState.needsTokenRefresh) {
                Log.i(
                    TAG,
                    "Access token for $accountId is null or needs refresh. Attempting refresh."
                )
                try {
                    val (newAuthState, refreshException) = appAuthHelperService.refreshAccessToken(
                        currentAuthState
                    )

                    if (refreshException != null) {
                        Log.e(TAG, "Access token refresh failed for $accountId", refreshException)
                        // Handle specific errors like invalid_grant
                        if (refreshException.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                            refreshException.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
                        ) {
                            Log.w(
                                TAG,
                                "Token refresh resulted in invalid_grant for $accountId. Clearing tokens locally."
                            )
                            tokenPersistenceService.clearTokens(
                                accountId,
                                removeAccountFromManager = false
                            ) // Keep account, clear tokens
                            return@withContext GoogleGetTokenResult.NeedsReauthentication(accountId)
                        }
                        return@withContext GoogleGetTokenResult.Error(
                            "Token refresh failed: ${refreshException.message}",
                            exception = refreshException
                        )
                    }

                    if (newAuthState?.accessToken == null) {
                        Log.e(TAG, "Token refresh did not yield a new access token for $accountId.")
                        return@withContext GoogleGetTokenResult.Error("Token refresh failed to produce a new access token.")
                    }

                    // 3. If refresh is successful, update the AuthState.
                    Log.d(
                        TAG,
                        "Access token refreshed successfully for $accountId. Updating persisted AuthState."
                    )
                    when (val updateResult =
                        tokenPersistenceService.updateAuthState(accountId, newAuthState)) {
                        is PersistenceResult.Success -> {
                            Log.i(
                                TAG,
                                "AuthState updated successfully for $accountId after token refresh."
                            )
                            return@withContext GoogleGetTokenResult.Success(newAuthState.accessToken!!) // Should be non-null
                        }

                        is PersistenceResult.Failure -> {
                            Log.e(
                                TAG,
                                "Failed to update AuthState for $accountId after refresh: ${updateResult.errorType}",
                                updateResult.cause
                            )
                            // Even if update fails, we have a new token in memory.
                            // Depending on policy, could return success or error.
                            // For now, return success with the new token but log the persistence error.
                            // Or, be strict:
                            return@withContext mapPersistenceErrorToGetTokenError(
                                updateResult,
                                "Failed to update AuthState after refresh"
                            )
                        }
                    }

                } catch (e: Exception) { // Catch unexpected exceptions during refresh logic
                    Log.e(TAG, "Unexpected exception during token refresh for $accountId", e)
                    return@withContext GoogleGetTokenResult.Error(
                        "Unexpected error during token refresh: ${e.message}",
                        exception = e
                    )
                }
            } else {
                // Token is valid and does not need refresh
                Log.d(TAG, "Access token for $accountId is fresh.")
                return@withContext GoogleGetTokenResult.Success(currentAuthState.accessToken!!) // Should be non-null if not needing refresh
            }
        }

    /**
     * Requests re-authentication for a given account by clearing its persisted tokens.
     * This is typically called when an operation indicates that the current tokens are
     * invalid and a fresh sign-in is required (e.g., due to an invalid_grant error
     * not caught during silent refresh, or other similar scenarios).
     *
     * Note: This method only clears the persisted authentication state (tokens).
     * It does not remove the account from AccountManager entirely, allowing the user
     * to potentially sign back into the same account.
     *
     * @param accountId The ID of the account that needs re-authentication.
     */
    suspend fun requestReauthentication(accountId: String): PersistenceResult<Unit> =
        withContext(ioDispatcher) {
            Log.i(
                TAG,
                "Requesting re-authentication for account ID: $accountId. Clearing local tokens."
            )
            // removeAccountFromManagerFlag = false ensures we only clear tokens, not the account itself.
            val clearResult =
                tokenPersistenceService.clearTokens(accountId, removeAccountFromManagerFlag = false)
            when (clearResult) {
                is PersistenceResult.Success -> {
                    Log.d(
                        TAG,
                        "Successfully cleared tokens for $accountId to force re-authentication."
                    )
                    // Optionally, notify ActiveGoogleAccountHolder if the account being re-authenticated was active,
                    // though clearing the active account might be too aggressive here if re-auth is quick.
                    // For now, just clear tokens. The UI layer should react to 'needsReauthentication' on the Account object.
                }

                is PersistenceResult.Failure -> {
                    Log.e(
                        TAG,
                        "Failed to clear tokens for $accountId during re-authentication request: ${clearResult.errorType}",
                        clearResult.cause
                    )
                }
            }
            return@withContext clearResult
        }
} 