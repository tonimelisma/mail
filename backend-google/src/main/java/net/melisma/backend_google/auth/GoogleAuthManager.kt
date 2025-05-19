package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.melisma.backend_google.BuildConfig
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.backend_google.model.ManagedGoogleAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.TokenResponse
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
                // Using a common redirect URI pattern. This should ideally be configured (e.g. BuildConfig or string resource)
                val redirectUri = Uri.parse("net.melisma.android:/oauth2redirect")

                // GOOGLE_ANDROID_CLIENT_ID must be available in BuildConfig of this module or provided via DI
                val actualClientId = BuildConfig.GOOGLE_ANDROID_CLIENT_ID
                val authRequest = appAuthHelperService.buildAuthorizationRequest(
                    loginHint = loginHint,
                    scopes = AppAuthHelperService.GMAIL_SCOPES,
                    clientId = actualClientId,
                    redirectUri = redirectUri
                )
                appAuthHelperService.createAuthorizationRequestIntent(authRequest)
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

        val tokenResponse: TokenResponse? = try {
            appAuthHelperService.exchangeAuthorizationCode(authResponse)
        } catch (e: AuthorizationException) {
            Log.e(TAG, "Token exchange failed with AuthorizationException", e)
            emit(GoogleSignInResult.Error("Token exchange failed: ${e.message}", e))
            return@flow
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed with generic Exception", e)
            emit(GoogleSignInResult.Error("Token exchange failed: ${e.message}", e))
            return@flow
        }

        if (tokenResponse == null) {
            Log.e(TAG, "Token exchange result is null after try-catch block")
            emit(GoogleSignInResult.Error("Token exchange failed: No token response received."))
            return@flow
        }

        Log.d(
            TAG,
            "Token exchange successful. ID Token present: ${tokenResponse.idToken != null}, Access Token present: ${tokenResponse.accessToken != null}"
        )

        val parsedTokenInfo = try {
            tokenResponse.idToken?.let { appAuthHelperService.parseIdToken(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ID token", e)
            emit(GoogleSignInResult.Error("Failed to parse ID token: ${e.message}", e))
            return@flow
        }

        if (parsedTokenInfo == null) {
            Log.e(TAG, "User info (parsedTokenInfo) is null after parsing ID token")
            emit(GoogleSignInResult.Error("Failed to retrieve user info from ID token."))
            return@flow
        }

        Log.d(TAG, "User info parsed: $parsedTokenInfo")

        // Ensure parsedTokenInfo and its userId are not null before calling saveTokens
        if (parsedTokenInfo == null || parsedTokenInfo.userId == null) {
            Log.e(TAG, "Parsed token info or userId is null before saving. This should not happen.")
            emit(GoogleSignInResult.Error("Critical error: User information missing after parsing token."))
            return@flow
        }
        // tokenResponse is also checked for null earlier.

        val persistenceOpResult = tokenPersistenceService.saveTokens(
            accountId = parsedTokenInfo.userId!!, // userId is now confirmed non-null
            email = parsedTokenInfo.email,
            displayName = parsedTokenInfo.displayName,
            photoUrl = parsedTokenInfo.picture,
            tokenResponse = tokenResponse!! // tokenResponse is confirmed non-null
        )
        when (val opResult = persistenceOpResult) {
            is PersistenceResult.Success -> {
                // val accountId = parsedTokenInfo.userId!! // Already have this
                Log.d(
                    TAG,
                    "Tokens and user info saved successfully for account ${parsedTokenInfo.userId}"
                )
                activeGoogleAccountHolder.setActiveAccountId(parsedTokenInfo.userId!!)
                val managedAccount = ManagedGoogleAccount(
                    accountId = parsedTokenInfo.userId!!,
                    email = parsedTokenInfo.email ?: "",
                    displayName = parsedTokenInfo.displayName,
                    photoUrl = parsedTokenInfo.picture
                )
                // AuthState is created with the latest tokenResponse from the exchange
                val newAuthState = AuthState(
                    authResponse!!,
                    tokenResponse,
                    authException
                ) // authResponse also non-null here
                emit(GoogleSignInResult.Success(managedAccount, newAuthState))
            }

            is PersistenceResult.Failure<*> -> {
                emit(
                    mapPersistenceErrorToSignInError(
                        opResult as PersistenceResult.Failure<GooglePersistenceErrorType>,
                        "Failed to save tokens"
                    )
                )
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
                            email = userInfo.email ?: "",
                            displayName = userInfo.displayName,
                            photoUrl = userInfo.photoUrl
                        )
                    )
                } else {
                    emit(null)
                }
            }

            is PersistenceResult.Failure<*> -> {
                val failure =
                    persistenceResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                Log.e(
                    TAG,
                    "Failed to get user info for $accountId: ${failure.errorType.name}",
                    failure.cause
                )
                emit(null) // Always emit null on any failure for this function
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in getAccount flow for $accountId", e)
        emit(null)
    }

    // Step 2.7: Implement GoogleAuthManager.getAccounts(): Flow<List<ManagedGoogleAccount>>
    fun getAccounts(): Flow<List<ManagedGoogleAccount>> = flow {
        Log.d(TAG, "Getting all Google accounts")
        when (val persistenceResult = tokenPersistenceService.getAllGoogleUserInfos()) {
            is PersistenceResult.Success -> {
                val managedAccounts = persistenceResult.data.mapNotNull { userInfo ->
                    userInfo.id?.let { actualId ->
                        ManagedGoogleAccount(
                            accountId = actualId,
                            email = userInfo.email ?: "",
                            displayName = userInfo.displayName,
                            photoUrl = userInfo.photoUrl
                        )
                    }
                }
                emit(managedAccounts)
            }

            is PersistenceResult.Failure<*> -> {
                val failure =
                    persistenceResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                Log.e(
                    TAG,
                    "Failed to get all user infos: ${failure.errorType.name}",
                    failure.cause
                )
                emit(emptyList()) // Always emit emptyList on any failure for this function
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in getAccounts flow", e)
        emit(emptyList())
    }

    // Step 2.8: Implement GoogleAuthManager.signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult>
    fun signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult> = flow {
        Log.d(TAG, "Signing out account: ${managedAccount.accountId}")

        val authStateResult = tokenPersistenceService.getAuthState(managedAccount.accountId)
        val authState: AuthState? = when (authStateResult) {
            is PersistenceResult.Success -> authStateResult.data
            is PersistenceResult.Failure<*> -> {
                val failure =
                    authStateResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                Log.w(
                    TAG,
                    "Could not retrieve AuthState for signOut, proceeding with local clear: ${failure.errorType} - ${failure.message}"
                )
                null
            }
        }

        if (authState?.refreshToken != null) {
            try {
                Log.d(TAG, "Attempting to revoke token for ${managedAccount.accountId}")
                appAuthHelperService.revokeToken(authState.refreshToken!!) 
            } catch (e: Exception) {
                Log.w(TAG, "Token revocation failed for ${managedAccount.accountId}", e)
            }
        }

        val clearTokenResult = tokenPersistenceService.clearTokens(
            accountId = managedAccount.accountId,
            removeAccountFromManagerFlag = true
        )

        when (val opResult = clearTokenResult) {
            is PersistenceResult.Success -> {
                Log.d(TAG, "Tokens cleared successfully for ${managedAccount.accountId}")
                if (activeGoogleAccountHolder.activeAccountId.value == managedAccount.accountId) {
                    activeGoogleAccountHolder.setActiveAccountId(null)
                }
                emit(GoogleSignOutResult.Success)
            }

            is PersistenceResult.Failure<*> -> {
                emit(
                    mapPersistenceErrorToSignOutError(
                        opResult as PersistenceResult.Failure<GooglePersistenceErrorType>,
                        "Failed to clear tokens"
                    )
                )
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Log.e(TAG, "Error in signOut flow for ${managedAccount.accountId}", e)
        emit(GoogleSignOutResult.Error("Sign out failed: ${e.message}", e))
    }

    // Step 2.9: Implement GoogleAuthManager.getFreshAccessToken(accountId: String): GoogleGetTokenResult (Suspend Function)
    suspend fun getFreshAccessToken(accountId: String): GoogleGetTokenResult {
        return withContext(ioDispatcher) {
            Log.d(TAG, "Attempting to get fresh access token for account: $accountId")
            val authStateResult = tokenPersistenceService.getAuthState(accountId)

            val currentAuthState: AuthState = when (val result = authStateResult) {
                is PersistenceResult.Success -> {
                    result.data ?: run {
                        Log.e(TAG, "No AuthState found for account $accountId during token fetch.")
                        return@withContext GoogleGetTokenResult.Error("No AuthState for account $accountId")
                    }
                }

                is PersistenceResult.Failure<*> -> {
                    val failure = result as PersistenceResult.Failure<GooglePersistenceErrorType>
                    Log.e(
                        TAG,
                        "Failed to get AuthState for $accountId: ${failure.errorType}",
                        failure.cause
                    )
                    return@withContext mapPersistenceErrorToGetTokenError(
                        failure,
                        "Failed to retrieve AuthState"
                    )
                }
            }

            if (currentAuthState.needsTokenRefresh == true) {
                Log.i(TAG, "Access token needs refresh for account $accountId.")
                try {
                    val refreshedTokenResponse =
                        appAuthHelperService.refreshAccessToken(currentAuthState)
                    if (refreshedTokenResponse != null) {
                        Log.d(TAG, "Token refreshed successfully for $accountId.")
                        currentAuthState.update(refreshedTokenResponse, null) // Update in place
                        val updatedAuthState = currentAuthState // Use the modified currentAuthState
                        when (val updateResult =
                            tokenPersistenceService.updateAuthState(accountId, updatedAuthState)) {
                            is PersistenceResult.Success -> {
                                Log.d(TAG, "Updated AuthState persisted for $accountId.")
                                refreshedTokenResponse.accessToken?.let {
                                    return@withContext GoogleGetTokenResult.Success(it)
                                } ?: run {
                                    Log.e(
                                        TAG,
                                        "Refreshed token response has null access token for $accountId."
                                    )
                                    return@withContext GoogleGetTokenResult.Error("Refreshed token is null for $accountId.")
                                }
                            }

                            is PersistenceResult.Failure<*> -> {
                                val failure =
                                    updateResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                                Log.e(
                                    TAG,
                                    "Failed to persist updated AuthState for $accountId: ${failure.errorType}",
                                    failure.cause
                                )
                                return@withContext mapPersistenceErrorToGetTokenError(
                                    failure,
                                    "Failed to save refreshed token"
                                )
                            }
                        }
                    } else {
                        Log.e(TAG, "Token refresh returned null response for $accountId.")
                        return@withContext GoogleGetTokenResult.Error("Token refresh failed for $accountId (null response).")
                    }
                } catch (e: AuthorizationException) {
                    Log.e(
                        TAG,
                        "AuthorizationException during token refresh for $accountId: ${e.type} - ${e.errorDescription}",
                        e
                    )
                    if (e.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && e.error == "invalid_grant") {
                        Log.w(
                            TAG,
                            "Token refresh failed with invalid_grant for $accountId. Clearing tokens and requiring re-auth."
                        )
                        val clearResult = tokenPersistenceService.clearTokens(
                            accountId,
                            removeAccountFromManagerFlag = false
                        )
                        if (clearResult is PersistenceResult.Failure<*>) {
                            val failure =
                                clearResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                            Log.w(
                                TAG,
                                "Failed to clear tokens after invalid_grant for $accountId: ${failure.errorType}",
                                failure.cause
                            )
                        }
                        return@withContext GoogleGetTokenResult.NeedsReauthentication(accountId)
                    }
                    return@withContext GoogleGetTokenResult.Error(
                        "Token refresh auth error for $accountId: ${e.message}",
                        exception = e
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during token refresh for $accountId", e)
                    return@withContext GoogleGetTokenResult.Error(
                        "Token refresh failed for $accountId: ${e.message}",
                        exception = e
                    )
                }
            } else {
                currentAuthState.accessToken?.let {
                    Log.d(TAG, "Using existing, non-expired access token for $accountId.")
                    return@withContext GoogleGetTokenResult.Success(it)
                } ?: run {
                    Log.e(
                        TAG,
                        "Current AuthState has null access token for $accountId (and doesn't need refresh)."
                    )
                    return@withContext GoogleGetTokenResult.Error("Missing access token in current AuthState for $accountId.")
                }
            }
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
    suspend fun requestReauthentication(accountId: String): PersistenceResult<Unit> {
        return withContext(ioDispatcher) {
            Log.w(TAG, "Requesting re-authentication for account: $accountId by clearing tokens.")
            val clearResult =
                tokenPersistenceService.clearTokens(accountId, removeAccountFromManagerFlag = false)
            when (clearResult) {
                is PersistenceResult.Success -> {
                    Log.i(TAG, "Tokens cleared for $accountId to force re-authentication.")
                    PersistenceResult.Success(Unit)
                }

                is PersistenceResult.Failure<*> -> {
                    val failure =
                        clearResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                    Log.e(
                        TAG,
                        "Failed to clear tokens for $accountId during re-authentication request: ${failure.errorType}",
                        failure.cause
                    )
                    PersistenceResult.Failure(failure.errorType, failure.message, failure.cause)
                }
            }
        }
    }
} 