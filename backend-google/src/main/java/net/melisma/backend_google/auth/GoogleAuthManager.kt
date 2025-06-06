package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import timber.log.Timber
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
    // Forcing recompilation

    // Public method to get the currently active account ID
    suspend fun getNullableActiveAccountId(): String? = withContext(ioDispatcher) {
        activeGoogleAccountHolder.getActiveAccountIdValue()
    }

    // Step 2.3: Helper Functions (Private)
    private fun mapPersistenceErrorToSignInError(
        failure: PersistenceResult.Failure<GooglePersistenceErrorType>,
        contextMessage: String
    ): GoogleSignInResult.Error {
        Timber.e(
            failure.cause,
            "$contextMessage: Persistence Error: ${failure.errorType}, Msg: ${failure.message}"
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
        Timber.e(
            failure.cause,
            "$contextMessage: Persistence Error: ${failure.errorType}, Msg: ${failure.message}"
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
        Timber.e(
            failure.cause,
            "$contextMessage: Persistence Error: ${failure.errorType}, Msg: ${failure.message}"
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
                val redirectUri = Uri.parse("net.melisma.mail:/oauth2redirect")
                val actualClientId = BuildConfig.GOOGLE_ANDROID_CLIENT_ID

                // Call buildAuthorizationRequest without the scopes parameter
                val authRequest = appAuthHelperService.buildAuthorizationRequest(
                    loginHint = loginHint,
                    // scopes parameter removed
                    clientId = actualClientId,
                    redirectUri = redirectUri
                )
                appAuthHelperService.createAuthorizationRequestIntent(authRequest)
            } catch (e: Exception) {
                Timber.e(e, "Failed to create sign-in intent")
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
            Timber.w(authException, "Authorization failed with exception")
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
            Timber.e("Authorization response is null without exception")
            emit(GoogleSignInResult.Error("Authorization failed: No response received."))
            return@flow
        }

        Timber.d("Handling authorization response: $authResponse")

        val tokenResponse: TokenResponse? = try {
            appAuthHelperService.exchangeAuthorizationCode(authResponse)
        } catch (e: AuthorizationException) {
            Timber.e(e, "Token exchange failed with AuthorizationException")
            emit(GoogleSignInResult.Error("Token exchange failed: ${e.message}", e))
            return@flow
        } catch (e: Exception) {
            Timber.e(e, "Token exchange failed with generic Exception")
            emit(GoogleSignInResult.Error("Token exchange failed: ${e.message}", e))
            return@flow
        }

        if (tokenResponse == null) {
            Timber.e("Token exchange result is null after try-catch block")
            emit(GoogleSignInResult.Error("Token exchange failed: No token response received."))
            return@flow
        }

        Timber.d(
            "Token exchange successful. ID Token present: ${tokenResponse.idToken != null}, Access Token present: ${tokenResponse.accessToken != null}"
        )

        // Verify that all requested scopes were granted
        val requestedScopesSet = AppAuthHelperService.RequiredScopes.toSet()
        val grantedScopesString = tokenResponse.scope
        val grantedScopesSet = grantedScopesString?.split(' ')?.toSet() ?: emptySet()

        if (!grantedScopesSet.containsAll(requestedScopesSet)) {
            Timber.w(
                "Not all requested Google scopes were granted. Requested: $requestedScopesSet, Granted: $grantedScopesSet. ID Token: ${tokenResponse.idToken}, Access Token: ${tokenResponse.accessToken}"
            )
            // Attempt to revoke tokens if any were issued, as the grant is incomplete
            tokenResponse.refreshToken?.let { appAuthHelperService.revokeToken(it) }

            // Use the correct static AuthorizationException instance for INVALID_REQUEST
            val invalidRequestException =
                AuthorizationException.AuthorizationRequestErrors.INVALID_REQUEST

            // It's better to use a more specific message or the description from the predefined exception if suitable.
            // For now, we'll keep the custom message but use the predefined exception object.
            emit(
                GoogleSignInResult.Error(
                    "Not all requested permissions were granted by the user. Please ensure all permissions are accepted to use the app.",
                    invalidRequestException // Pass the direct static AuthorizationException instance
                )
            )
            return@flow
        }
        Timber.i(
            "All requested Google scopes have been granted: ${grantedScopesSet.joinToString()}"
        )

        val parsedTokenInfo = try {
            tokenResponse.idToken?.let { appAuthHelperService.parseIdToken(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse ID token")
            emit(GoogleSignInResult.Error("Failed to parse ID token: ${e.message}", e))
            return@flow
        }

        if (parsedTokenInfo == null) {
            Timber.e("User info (parsedTokenInfo) is null after parsing ID token")
            emit(GoogleSignInResult.Error("Failed to retrieve user info from ID token."))
            return@flow
        }

        Timber.d("User info parsed: $parsedTokenInfo")

        // Ensure parsedTokenInfo and its userId are not null before calling saveTokens
        if (parsedTokenInfo.userId == null) {
            Timber.e("Parsed token info or userId is null before saving. This should not happen.")
            emit(GoogleSignInResult.Error("Critical error: User information missing after parsing token."))
            return@flow
        }
        // tokenResponse is also checked for null earlier.

        val persistenceOpResult = tokenPersistenceService.saveTokens(
            accountId = parsedTokenInfo.userId!!, // userId is now confirmed non-null
            email = parsedTokenInfo.email,
            displayName = parsedTokenInfo.displayName,
            photoUrl = parsedTokenInfo.picture,
            authResponse = authResponse!!, // Pass the original authResponse
            tokenResponse = tokenResponse!! // tokenResponse is confirmed non-null
        )
        when (val opResult = persistenceOpResult) {
            is PersistenceResult.Success -> {
                // val accountId = parsedTokenInfo.userId!! // Already have this
                Timber.d(
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
                        @Suppress("UNCHECKED_CAST") (opResult as PersistenceResult.Failure<GooglePersistenceErrorType>),
                        "Failed to save tokens"
                    )
                )
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Timber.e(e, "Error in handleAuthorizationResponse flow")
        emit(GoogleSignInResult.Error("An unexpected error occurred: ${e.message}", e))
    }

    // Step 2.6: Implement GoogleAuthManager.getAccount(accountId: String): Flow<ManagedGoogleAccount?>
    fun getAccount(accountId: String): Flow<ManagedGoogleAccount?> = flow {
        Timber.d("Getting account for ID: $accountId")
        when (val persistenceResult = tokenPersistenceService.getUserInfo(accountId)) {
            is PersistenceResult.Success -> {
                val userInfo = persistenceResult.data
                emit(
                    ManagedGoogleAccount(
                        accountId = userInfo.id,
                        email = userInfo.email ?: "",
                        displayName = userInfo.displayName,
                        photoUrl = userInfo.photoUrl
                    )
                )
            }

            is PersistenceResult.Failure<*> -> {
                @Suppress("UNCHECKED_CAST")
                val failure =
                    persistenceResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                Timber.e(
                    failure.cause,
                    "Failed to get user info for $accountId: ${failure.errorType.name}"
                )
                emit(null) // Always emit null on any failure for this function
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Timber.e(e, "Error in getAccount flow for $accountId")
        emit(null)
    }

    // Step 2.7: Implement GoogleAuthManager.getAccounts(): Flow<List<ManagedGoogleAccount>>
    fun getAccounts(): Flow<List<ManagedGoogleAccount>> = flow {
        Timber.d("Getting all Google accounts")
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
                @Suppress("UNCHECKED_CAST")
                val failure =
                    persistenceResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                Timber.e(
                    failure.cause,
                    "Failed to get all user infos: ${failure.errorType.name}"
                )
                emit(emptyList()) // Always emit emptyList on any failure for this function
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Timber.e(e, "Error in getAccounts flow")
        emit(emptyList())
    }

    // Step 2.8: Implement GoogleAuthManager.signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult>
    fun signOut(accountId: String): Flow<GoogleSignOutResult> = flow {
        Timber.d("Signing out account: $accountId")

        val authStateResult = tokenPersistenceService.getAuthState(accountId)
        val authState: AuthState? = when (authStateResult) {
            is PersistenceResult.Success -> authStateResult.data
            is PersistenceResult.Failure<*> -> {
                @Suppress("UNCHECKED_CAST")
                val failure =
                    authStateResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                Timber.w(
                    "Could not retrieve AuthState for signOut, proceeding with local clear: ${failure.errorType} - ${failure.message}"
                )
                null
            }
        }

        if (authState?.refreshToken != null) {
            try {
                Timber.d("Attempting to revoke token for $accountId")
                appAuthHelperService.revokeToken(authState.refreshToken!!) 
            } catch (e: Exception) {
                Timber.w(e, "Token revocation failed for $accountId")
            }
        }

        val clearTokenResult = tokenPersistenceService.clearTokens(
            accountId = accountId,
            removeAccountFromManagerFlag = true
        )

        when (val opResult = clearTokenResult) {
            is PersistenceResult.Success -> {
                Timber.d("Tokens cleared successfully for $accountId")
                if (activeGoogleAccountHolder.activeAccountId.value == accountId) {
                    activeGoogleAccountHolder.setActiveAccountId(null)
                }
                emit(GoogleSignOutResult.Success)
            }

            is PersistenceResult.Failure<*> -> {
                emit(
                    mapPersistenceErrorToSignOutError(
                        @Suppress("UNCHECKED_CAST") (opResult as PersistenceResult.Failure<GooglePersistenceErrorType>),
                        "Failed to clear tokens"
                    )
                )
            }
        }
    }.flowOn(ioDispatcher).catch { e ->
        Timber.e(e, "Error in signOut flow for $accountId")
        emit(GoogleSignOutResult.Error("Sign out failed: ${e.message}", e))
    }

    // Step 2.9: Implement GoogleAuthManager.getFreshAccessToken(accountId: String): GoogleGetTokenResult (Suspend Function)
    suspend fun getFreshAccessToken(accountId: String): GoogleGetTokenResult {
        return withContext(ioDispatcher) {
            Timber.d("Attempting to get fresh access token for account: $accountId")
            val authStateResult = tokenPersistenceService.getAuthState(accountId)

            val currentAuthState: AuthState = when (val result = authStateResult) {
                is PersistenceResult.Success -> {
                    result.data ?: run {
                        Timber.e("No AuthState found for account $accountId during token fetch.")
                        return@withContext GoogleGetTokenResult.Error("No AuthState for account $accountId")
                    }
                }

                is PersistenceResult.Failure<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val failure = result as PersistenceResult.Failure<GooglePersistenceErrorType>
                    Timber.e(
                        failure.cause,
                        "Failed to get AuthState for $accountId: ${failure.errorType}"
                    )
                    return@withContext mapPersistenceErrorToGetTokenError(
                        failure,
                        "Failed to retrieve AuthState"
                    )
                }
            }

            Timber.d(
                "getFreshAccessToken - AccountID: $accountId - Retrieved AuthState ID: ${
                    System.identityHashCode(currentAuthState)
                }"
            )
            Timber.d("getFreshAccessToken - AccountID: $accountId - Retrieved AuthState JSON: ${currentAuthState.jsonSerializeString()}")
            Timber.d("getFreshAccessToken - AccountID: $accountId - Retrieved AuthState Config JSON: ${currentAuthState.authorizationServiceConfiguration?.toJsonString()}")
            Timber.d("getFreshAccessToken - AccountID: $accountId - Retrieved AuthState Last Auth Resp Config JSON: ${currentAuthState.lastAuthorizationResponse?.request?.configuration?.toJsonString()}")
            Timber.d("getFreshAccessToken - AccountID: $accountId - Retrieved AuthState Refresh Token Present: ${currentAuthState.refreshToken != null}")

            Timber.i(
                "Access token needs refresh for account $accountId (or assumed to by compiler warning)."
            )
            Timber.d(
                "getFreshAccessToken - AccountID: $accountId - AuthState ID before refresh call: ${
                    System.identityHashCode(currentAuthState)
                }"
            )
            Timber.d("getFreshAccessToken - AccountID: $accountId - AuthState Config JSON before refresh call: ${currentAuthState.authorizationServiceConfiguration?.toJsonString()}")
            try {
                val refreshedTokenResponse =
                    appAuthHelperService.refreshAccessToken(currentAuthState)
                Timber.d("Token refreshed successfully for $accountId.")
                currentAuthState.update(refreshedTokenResponse, null) // Update in place
                Timber.d("getFreshAccessToken - AccountID: $accountId - AuthState after refresh update, Config JSON: ${currentAuthState.authorizationServiceConfiguration?.toJsonString()}")
                Timber.d("getFreshAccessToken - AccountID: $accountId - AuthState after refresh update, JSON: ${currentAuthState.jsonSerializeString()}")
                val updatedAuthState = currentAuthState // Use the modified currentAuthState
                when (val updateResult =
                    tokenPersistenceService.updateAuthState(accountId, updatedAuthState)) {
                    is PersistenceResult.Success -> {
                        Timber.d("Updated AuthState persisted for $accountId.")
                        refreshedTokenResponse.accessToken?.let {
                            return@withContext GoogleGetTokenResult.Success(it)
                        } ?: run {
                            Timber.e(
                                "Refreshed token response has null access token for $accountId."
                            )
                            return@withContext GoogleGetTokenResult.Error("Refreshed token is null for $accountId.")
                        }
                    }

                    is PersistenceResult.Failure<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val failure =
                            updateResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                        Timber.e(
                            failure.cause,
                            "Failed to persist updated AuthState for $accountId: ${failure.errorType}"
                        )
                        return@withContext mapPersistenceErrorToGetTokenError(
                            failure,
                            "Failed to save refreshed token"
                        )
                    }
                }
            } catch (e: AuthorizationException) {
                Timber.e(
                    e,
                    "AuthorizationException during token refresh for $accountId: ${e.type} - ${e.errorDescription}"
                )
                if (e.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && e.error == "invalid_grant") {
                    Timber.w(
                        "Token refresh failed with invalid_grant for $accountId. Clearing tokens and requiring re-auth."
                    )
                    val clearResult = tokenPersistenceService.clearTokens(
                        accountId,
                        removeAccountFromManagerFlag = false
                    )
                    if (clearResult is PersistenceResult.Failure<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val failure =
                            clearResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                        Timber.w(
                            failure.cause,
                            "Failed to clear tokens after invalid_grant for $accountId: ${failure.errorType}"
                        )
                    }
                    return@withContext GoogleGetTokenResult.NeedsReauthentication(accountId)
                }
                return@withContext GoogleGetTokenResult.Error(
                    "Token refresh auth error for $accountId: ${e.message}",
                    exception = e
                )
            } catch (e: Exception) {
                Timber.e(e, "Exception during token refresh for $accountId")
                return@withContext GoogleGetTokenResult.Error(
                    "Token refresh failed for $accountId: ${e.message}",
                    exception = e
                )
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
            Timber.w("Requesting re-authentication for account: $accountId by clearing tokens.")
            val clearResult =
                tokenPersistenceService.clearTokens(accountId, removeAccountFromManagerFlag = false)
            when (clearResult) {
                is PersistenceResult.Success -> {
                    Timber.i("Tokens cleared for $accountId to force re-authentication.")
                    PersistenceResult.Success(Unit)
                }

                is PersistenceResult.Failure<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val failure =
                        clearResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                    Timber.e(
                        failure.cause,
                        "Failed to clear tokens for $accountId during re-authentication request: ${failure.errorType}"
                    )
                    PersistenceResult.Failure(failure.errorType, failure.message, failure.cause)
                }
            }
        }
    }
} 