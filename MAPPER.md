# Plan for Mapping Layer in :backend-microsoft

**Objective:** Modify `:backend-microsoft` (specifically `MicrosoftAccountRepository`) and related
components so that `DefaultAccountRepository` (in `:data`) no longer needs to be aware of
MSAL-specific types like `IAccount`, `IAuthenticationResult`, or `MsalException`. This will allow
changing the `api(project(\":backend-microsoft\"))` dependency in `:data` to
`implementation(project(\":backend-microsoft\"))`.

**Current Situation Analysis (Recap):**

1. `MicrosoftAuthManager` (in `:backend-microsoft`) uses MSAL and exposes
   `Flow<AuthenticationResultWrapper>` and `Flow<SignOutResultWrapper>`.
2. `AuthenticationResultWrapper` and `SignOutResultWrapper` (defined in `:backend-microsoft`)
   contain MSAL types (`IAccount`, `IAuthenticationResult`, `MsalException`).
3. `MicrosoftAccountRepository` (in `:backend-microsoft`) uses `MicrosoftAuthManager`. Its public
   API (methods like `signIn`, `signOut` which would be derived from or similar to the
   `AccountRepository` interface) currently effectively expose these MSAL types or their wrappers to
   `DefaultAccountRepository` in `:data`.
4. This necessitates `:data` having `api` access to `:backend-microsoft` (and transitively to MSAL)
   for type resolution.

**Core Strategy:**

1. Define generic, non-MSAL-specific authentication result types in `:core-data`.
2. Modify `AccountRepository` interface in `:core-data` to use these generic result types for
   `signIn` and `signOut` methods, ensuring a consistent API for all authentication providers.
3. Modify `MicrosoftAccountRepository` in `:backend-microsoft` to:
    * Implement the updated `AccountRepository` interface.
    * Internally use the MSAL-specific `AuthenticationResultWrapper` and `SignOutResultWrapper` from
      `MicrosoftAuthManager`.
    * Map these MSAL-specific results to the new generic result types before returning them in its
      public API.
4. Modify `DefaultAccountRepository` in `:data` to:
    * Conform to the updated `AccountRepository` interface.
    * For Microsoft, delegate to `MicrosoftAccountRepository`'s new methods.
    * For Google (AppAuth), adapt its existing AppAuth logic to fit the new `signIn` and `signOut`
      signatures, managing the intent launch and result processing to ultimately return
      `GenericAuthResult` and `GenericSignOutResult`.
5. Update `:app` (ViewModels/UI) to call the new `AccountRepository` methods and handle these
   generic types, including the two-step nature of Google sign-in (initiating, then launching an
   intent).
6. Change the Gradle dependency in `:data/build.gradle.kts`.

---

**Detailed Steps and File Changes:**

**Phase 1: Define Generic Authentication Result Types**

1. **Create/Modify File:**
   `core-data/src/main/java/net/melisma/core_data/model/AuthResultModels.kt` (New File or Existing
   if partially present)
    * **Action:** Define new sealed classes/enums for generic authentication results.
    * **Code:**
      ```kotlin
      package net.melisma.core_data.model

      // Assumes Account model (net.melisma.core_data.model.Account) is already generic and suitable.
      // data class Account(val id: String, val username: String, val providerType: String, val needsReauthentication: Boolean = false)

      sealed class GenericAuthResult {
          data class Success(val account: Account) : GenericAuthResult()
          /**
           * Indicates that an external UI step is required (e.g., launching an Intent for AppAuth).
           * The Intent should be obtained via a separate mechanism (e.g., a dedicated flow or method in the repository).
           */
          data class UiActionRequired(val intent: android.content.Intent) : GenericAuthResult()
          data class Error(
              val message: String,
              val type: GenericAuthErrorType,
              val providerSpecificErrorCode: String? = null, // For logging or very specific cases
              // isUiRequiredForResolution is specific to MSAL's MsalUiRequiredException.
              // Other UI required scenarios (like needing to re-launch interactive sign-in)
              // should be modeled by the ViewModel based on error type or specific flags.
              // For MSAL's specific MsalUiRequiredException, this can be a specific error type or a flag.
              val msalRequiresInteractiveSignIn: Boolean = false
          ) : GenericAuthResult()
          object Cancelled : GenericAuthResult()
          // InProgress is removed to simplify the states returned by signIn.
          // Loading states should be managed by the ViewModel observing the flow.
      }

      enum class GenericAuthErrorType {
          NETWORK_ERROR,
          SERVICE_UNAVAILABLE, // e.g., MSAL not initialized, AppAuth misconfiguration
          AUTHENTICATION_FAILED, // General auth failure, wrong credentials, token exchange failure
          AUTHORIZATION_DENIED, // User denied permissions
          TOKEN_OPERATION_FAILED, // Failed to get/refresh/revoke token
          ACCOUNT_NOT_FOUND,
          INVALID_REQUEST,
          OPERATION_CANCELLED, // Explicit cancellation by user
          MSAL_INTERACTIVE_AUTH_REQUIRED, // Specific for MsalUiRequiredException
          UNKNOWN_ERROR
      }

      sealed class GenericSignOutResult {
          object Success : GenericSignOutResult()
          data class Error(
              val message: String,
              val type: GenericAuthErrorType, // Re-use GenericAuthErrorType
              val providerSpecificErrorCode: String? = null
          ) : GenericSignOutResult()
      }
      ```
    * **Note:** `GenericAuthResult.UiActionRequired` is introduced for the Google AppAuth flow.
      `InProgress` is removed from `GenericAuthResult` as ViewModels can manage loading state while
      collecting the flow. `msalRequiresInteractiveSignIn` flag added to `GenericAuthResult.Error`
      for clarity on `MsalUiRequiredException`.

**Phase 2: Refactor `:backend-microsoft` Layer**

1. **File:**
   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt`
    * **Action:** Implement the updated `AccountRepository` interface. Change existing methods (like
      `getAuthenticationIntentRequest` or `initiateSignIn` and `suspend fun signOut`) to the new
      `signIn` and `signOut` signatures. Map MSAL-specific wrappers to generic result types.
    * **Key Changes:**
        * Modify/Implement
          `signIn(activity: Activity, loginHint: String?): Flow<GenericAuthResult>`:
            * Call `microsoftAuthManager.signInInteractive(activity, scopes, loginHint)`.
            * Collect from its `Flow<AuthenticationResultWrapper>`.
            * Map `AuthenticationResultWrapper.Success` to `GenericAuthResult.Success(coreAccount)`.
              Ensure `IAccount` is correctly mapped to `core_data.model.Account` (this mapping
              already occurs in `getAccounts`, reuse/ensure consistency). Update
              `activeMicrosoftAccountHolder`.
            * Map `AuthenticationResultWrapper.Error` to `GenericAuthResult.Error(...)`. Use
              `MicrosoftErrorMapper`. If `exception is MsalUiRequiredException`, set
              `msalRequiresInteractiveSignIn = true` and potentially use
              `GenericAuthErrorType.MSAL_INTERACTIVE_AUTH_REQUIRED`.
            * Map `AuthenticationResultWrapper.Cancelled` to `GenericAuthResult.Cancelled`.
        * Modify/Implement `signOut(account: Account): Flow<GenericSignOutResult>`:
            * Call `microsoftAuthManager.signOut(msalAccount)`.
            * Collect from its `Flow<SignOutResultWrapper>`.
            * Map `SignOutResultWrapper.Success` to `GenericSignOutResult.Success`. Update
              `activeMicrosoftAccountHolder`.
            * Map `SignOutResultWrapper.Error` to `GenericSignOutResult.Error(...)`, using
              `MicrosoftErrorMapper`.
    * **Illustrative Snippet for `signIn` method:**
      ```kotlin
      // Inside MicrosoftAccountRepository.kt
      // This replaces the existing getAuthenticationIntentRequest or initiateSignIn
      override fun signIn(activity: Activity, loginHint: String? /* from AccountRepository */): Flow<GenericAuthResult> {
          // Potentially use a different set of scopes if loginHint implies specific resource access
          val scopes = MicrosoftAuthManager.MICROSOFT_SCOPES 
          return microsoftAuthManager.signInInteractive(activity, scopes, loginHint) // Assuming signInInteractive can take loginHint
              .map { msalResult -> // msalResult is AuthenticationResultWrapper
                  when (msalResult) {
                      is AuthenticationResultWrapper.Success -> {
                          // val coreAccount = Account( /* map IAccount to core_data.Account */ )
                          // Placeholder for mapping logic (ensure id, username, providerType are correctly populated)
                          val coreAccount = Account(
                              id = msalResult.account.id ?: UUID.randomUUID().toString(), // Robust ID handling
                              username = msalResult.account.username ?: "Unknown MS User",
                              providerType = Account.PROVIDER_TYPE_MS,
                              needsReauthentication = false // Fresh sign-in
                          )
                          activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(coreAccount.id)
                          GenericAuthResult.Success(coreAccount)
                      }
                      is AuthenticationResultWrapper.Error -> {
                          val isUiRequired = msalResult.exception is MsalUiRequiredException
                          val mappedError = microsoftErrorMapper.mapAuthExceptionToGenericAuthError(msalResult.exception)
                          GenericAuthResult.Error(
                              message = mappedError.message,
                              type = mappedError.type,
                              providerSpecificErrorCode = msalResult.exception.errorCode,
                              msalRequiresInteractiveSignIn = isUiRequired
                          )
                      }
                      is AuthenticationResultWrapper.Cancelled -> GenericAuthResult.Cancelled
                  }
              }
      }

      // Illustrative Snippet for signOut method
      override fun signOut(account: Account): Flow<GenericSignOutResult> {
          // Find the MSAL IAccount corresponding to the generic Account
          // val msalAccount = findMsalAccountById(account.id) // Simplified; robust lookup needed
          // This logic likely already exists or needs to be solid
          return flow { // Example, actual implementation detail may vary
              val msalAccount = microsoftAuthManager.getAccounts().firstOrNull()?.find { it.id == account.id }
              if (msalAccount == null) {
                  emit(GenericSignOutResult.Error("MS Account not found for sign out.", GenericAuthErrorType.ACCOUNT_NOT_FOUND))
                  return@flow
              }

              microsoftAuthManager.signOut(msalAccount)
                  .map { signOutResult ->
                      when (signOutResult) {
                          is SignOutResultWrapper.Success -> {
                              if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                                  activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                              }
                              GenericSignOutResult.Success
                          }
                          is SignOutResultWrapper.Error -> {
                              val mappedError = microsoftErrorMapper.mapSignOutExceptionToGenericAuthError(signOutResult.exception)
                              GenericSignOutResult.Error(
                                  message = mappedError.message,
                                  type = mappedError.type,
                                  providerSpecificErrorCode = signOutResult.exception.errorCode
                              )
                          }
                      }
                  }.collect { emit(it) }
          }
      }
      ```

2. **File:**
   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/errors/MicrosoftErrorMapper.kt`
    * **Action:** Enhance to map `MsalException` (and other potential exceptions) to a structure
      like `MappedAuthErrorHolder(val message: String, val type: GenericAuthErrorType)`.
    * **Code Sketch (Illustrative - internal structure for the mapper):**
      ```kotlin
      package net.melisma.backend_microsoft.errors

      import com.microsoft.identity.client.exception.*
      import net.melisma.core_data.model.GenericAuthErrorType // Ensure this is the correct import

      // Internal holder or direct return, adapted for the new GenericAuthResult.Error structure
      internal data class MappedAuthErrorDetails(val message: String, val type: GenericAuthErrorType)

      class MicrosoftErrorMapper @Inject constructor() {
          // ... (existing methods for user messages if still needed elsewhere)

          fun mapAuthExceptionToGenericAuthError(exception: Throwable?): MappedAuthErrorDetails {
              return when (exception) {
                  is MsalUiRequiredException -> MappedAuthErrorDetails(
                      exception.message ?: "Your session has expired. Please sign in again.",
                      GenericAuthErrorType.MSAL_INTERACTIVE_AUTH_REQUIRED // Specific type
                  )
                  is MsalUserCancelException -> MappedAuthErrorDetails(
                      exception.message ?: "Operation cancelled by user.",
                      GenericAuthErrorType.OPERATION_CANCELLED
                  )
                  is MsalClientException -> {
                      // map specific MsalClientException.errorCodes to message and GenericAuthErrorType
                      val msg = exception.message ?: "A client error occurred during Microsoft authentication."
                      val type = when(exception.errorCode) {
                          MsalClientException.NO_CURRENT_ACCOUNT,
                          MsalClientException.NO_ACCOUNT_FOUND -> GenericAuthErrorType.ACCOUNT_NOT_FOUND
                          MsalClientException.INVALID_PARAMETER -> GenericAuthErrorType.INVALID_REQUEST
                          // Add more mappings here
                          else -> GenericAuthErrorType.AUTHENTICATION_FAILED
                      }
                      MappedAuthErrorDetails(msg, type)
                  }
                  is MsalServiceException -> {
                      // map specific MsalServiceException.errorCodes
                      val msg = exception.message ?: "A service error occurred with Microsoft authentication."
                      val type = when(exception.errorCode) {
                          // Add specific service error code mappings here
                          else -> GenericAuthErrorType.SERVICE_UNAVAILABLE // Or AUTHENTICATION_FAILED
                      }
                      MappedAuthErrorDetails(msg, type)
                  }
                  is java.io.IOException -> MappedAuthErrorDetails(
                      exception.message ?: "Network error.",
                      GenericAuthErrorType.NETWORK_ERROR
                  )
                  else -> MappedAuthErrorDetails(
                      exception?.message ?: "An unknown error occurred.",
                      GenericAuthErrorType.UNKNOWN_ERROR
                  )
              }
          }

          fun mapSignOutExceptionToGenericAuthError(exception: Throwable?): MappedAuthErrorDetails {
              // Similar mapping logic for sign-out specific errors, can reuse parts of mapAuthExceptionToGenericAuthError
               return when (exception) {
                  is MsalClientException, is MsalServiceException -> { // Example
                      val details = mapAuthExceptionToGenericAuthError(exception) // Reuse logic if applicable
                      MappedAuthErrorDetails("Sign out failed: ${details.message}", details.type)
                  }
                   is java.io.IOException -> MappedAuthErrorDetails(
                       exception.message ?: "Network error during sign out.",
                       GenericAuthErrorType.NETWORK_ERROR
                   )
                  else -> MappedAuthErrorDetails(
                      exception?.message ?: "An unknown error occurred during sign out.",
                      GenericAuthErrorType.UNKNOWN_ERROR
                  )
              }
          }
      }
      ```

**Phase 3: Update `:core-data` Interface**

1. **File:** `core-data/src/main/java/net/melisma/core_data/repository/AccountRepository.kt`
    * **Action:** Modify method signatures to use generic result types. The
      `getAuthenticationIntentRequest` and `handleAuthenticationResult` roles will be absorbed or
      changed by the new `signIn` method, especially for Google.
    * **Code Sketch:**
      ```kotlin
      package net.melisma.core_data.repository

      import android.app.Activity
      // import android.content.Intent // No longer needed for signIn directly
      import kotlinx.coroutines.flow.Flow
      import net.melisma.core_data.model.Account
      import net.melisma.core_data.model.GenericAuthResult
      import net.melisma.core_data.model.GenericSignOutResult

      interface AccountRepository {
          // ... (getAccounts(), getActiveAccount(), overallApplicationAuthState remain similar)
          fun getAccounts(): Flow<List<Account>>
          fun getActiveAccount(providerType: String): Flow<Account?>
          val overallApplicationAuthState: Flow<OverallApplicationAuthState> // Assuming StateFlow or Flow

          /**
           * Initiates the sign-in process for the given provider.
           *
           * For MSAL: Returns a Flow that will directly emit Success, Error, or Cancelled.
           * For Google (AppAuth): Returns a Flow that may first emit UiActionRequired(intent)
           * if user interaction is needed. The caller must launch this intent.
           * The final result (Success, Error, Cancelled) will be emitted on the SAME Flow
           * after handleAuthenticationResult() is called by the Activity/Fragment.
           *
           * @param activity The current Activity, crucial for providers needing UI context.
           * @param loginHint Optional hint for the provider, e.g., email address.
           * @return A Flow emitting the authentication result.
           */
          fun signIn(activity: Activity, loginHint: String? = null, providerType: String): Flow<GenericAuthResult>

          /**
           * Handles the result from an external authentication activity (e.g., AppAuth's redirect).
           * This is crucial for the Google sign-in flow to complete the signIn Flow.
           *
           * @param providerType The provider type for which the result is being handled.
           * @param resultCode The result code from the Activity (e.g., Activity.RESULT_OK).
           * @param data The Intent data returned from the Activity.
           */
          suspend fun handleAuthenticationResult(providerType: String, resultCode: Int, data: android.content.Intent?)
          // Removed activity from handleAuthenticationResult as DefaultAccountRepository can hold context if needed or get it passed down.

          fun signOut(account: Account): Flow<GenericSignOutResult>

          // observeActionMessages() might still be useful for other notifications,
          // but primary success/error/cancel for signIn/signOut should come from their respective Flows.
          fun observeActionMessages(): Flow<String?>
          fun clearActionMessage()

          suspend fun markAccountForReauthentication(accountId: String, providerType: String)
          // ...
      }
      ```
    * **Key Change Justification**: `signIn` now aims to be the single entry point. For Google, it
      will emit `UiActionRequired(intent)`. The `handleAuthenticationResult` in the repository is
      now primarily a helper for the Google `signIn` flow to process the AppAuth callback and allow
      the original `signIn` flow to proceed with `Success` or `Error`.

**Phase 4: Update `:data` Module Implementation**

1. **File:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
    * **Action:** Conform to the updated `AccountRepository` interface.
    * **Key Changes for `signIn` (Google path):**
        * The `signIn` method for Google will use a `callbackFlow` or a combination of `SharedFlow`
          and `mapLatest` to manage the multi-step AppAuth process.
        * When `signIn` (Google) is called:
            1. It immediately tries to get an `AuthorizationIntent` from `appAuthHelperService`.
            2. It emits `GenericAuthResult.UiActionRequired(intent)` on the Flow.
            3. It then *suspends* or *awaits* a signal that `handleAuthenticationResult` has been
               called for this specific request. This can be done using a `Channel` or a
               request-scoped `CompletableDeferred`/`SharedFlow`.
            4. When `handleAuthenticationResult` (Google) is called by the Activity/Fragment:
                * It processes the AppAuth response (exchanges code, gets tokens, creates
                  `Account`).
                * It then signals the waiting `signIn` Flow (e.g., sends the
                  `GenericAuthResult.Success` or `Error` to the Channel/SharedFlow) which then emits
                  it to the original ViewModel collector.
    * **Key Changes for `signOut` (Google path):**
        * Adapt existing Google sign-out logic (token revocation, local cache clearing) to return
          `Flow<GenericSignOutResult>`.
    * **Illustrative `signIn` delegation (focus on Google complexity):**
      ```kotlin
      // Inside DefaultAccountRepository.kt

      // Mechanism to correlate handleAuthenticationResult with an ongoing signIn flow for Google.
      // This is a simplified example; a more robust request-correlation mechanism might be needed
      // if multiple Google sign-ins can be concurrent (usually not the case).
      private val googleAuthResultChannel = Channel<GenericAuthResult>(Channel.CONFLATED)

      override fun signIn(activity: Activity, loginHint: String?, providerType: String): Flow<GenericAuthResult> {
          return when (providerType.uppercase()) {
              Account.PROVIDER_TYPE_MS -> {
                  microsoftAccountRepository.signIn(activity, loginHint)
                      .onEach { result ->
                          if (result is GenericAuthResult.Success) {
                              // Update combined account list, overall auth state
                              updateCombinedAccountsAndOverallAuthState()
                          }
                      }
              }
              Account.PROVIDER_TYPE_GOOGLE -> callbackFlow {
                  Timber.d("Google signIn initiated in DefaultAccountRepository")
                  try {
                      // Step 1: Get and emit the Intent for UI action
                      val authRequestIntent = appAuthHelperService.getAuthorizationRequestIntent(
                          /* params: clientId, redirectUri, scopes, loginHint */
                          loginHint = loginHint // Pass loginHint
                      ) // Assuming AppAuthHelperService is updated or has such a method
                      
                      trySend(GenericAuthResult.UiActionRequired(authRequestIntent))
                      Timber.d("Emitted UiActionRequired for Google sign-in.")

                      // Step 2: Wait for the result from handleAuthenticationResult via the channel
                      // This will suspend the callbackFlow until a result is sent to the channel
                      val finalResult = googleAuthResultChannel.receive()
                      Timber.d("Received final Google sign-in result from channel: $finalResult")
                      trySend(finalResult)

                      if (finalResult is GenericAuthResult.Success) {
                         updateCombinedAccountsAndOverallAuthState()
                      }

                  } catch (e: Exception) {
                      Timber.e(e, "Error during Google AppAuth signIn setup.")
                      trySend(GenericAuthResult.Error("Failed to start Google sign-in: ${e.message}", GenericAuthErrorType.UNKNOWN_ERROR))
                  }
                  awaitClose { Timber.d("Google signIn callbackFlow closed.") }
              }
              else -> flowOf(GenericAuthResult.Error("Unsupported provider: $providerType", GenericAuthErrorType.INVALID_REQUEST))
          }
      }

      override suspend fun handleAuthenticationResult(providerType: String, resultCode: Int, data: Intent?) {
          if (providerType.uppercase() == Account.PROVIDER_TYPE_GOOGLE) {
              Timber.d("Handling Google AppAuth result in DefaultAccountRepository")
              // Current logic from handleGoogleAppAuthResponse:
              // 1. Parse AuthorizationResponse, AuthorizationException from data.
              // 2. If success (authResponse != null):
              //    a. Exchange authorization code for TokenResponse using appAuthHelperService.
              //    b. Parse ID token, get user info.
              //    c. Save tokens and user info using googleTokenPersistenceService.
              //    d. Map to core_data.model.Account.
              //    e. Emit GenericAuthResult.Success(newAccount) to googleAuthResultChannel.
              // 3. If error or cancellation:
              //    a. Map to GenericAuthResult.Error or GenericAuthResult.Cancelled.
              //    b. Emit to googleAuthResultChannel.

              // Example (simplified, needs full logic from existing handleGoogleAppAuthResponse):
              if (resultCode == Activity.RESULT_OK && data != null) {
                  val authResponse = AuthorizationResponse.fromIntent(data)
                  val authException = AuthorizationException.fromIntent(data)

                  if (authResponse != null) {
                      try {
                          val tokenResponse = appAuthHelperService.exchangeAuthorizationCode(authResponse) // Ensure this is suspend or on IO
                          val idToken = tokenResponse.idToken
                          if (idToken == null) { /* ... error ... */ googleAuthResultChannel.trySend(GenericAuthResult.Error(/*...*/)); return }
                          val parsedInfo = appAuthHelperService.parseIdToken(idToken)
                          if (parsedInfo?.userId == null) { /* ... error ... */ googleAuthResultChannel.trySend(GenericAuthResult.Error(/*...*/)); return }
                          
                          googleTokenPersistenceService.saveTokens(/*...*/)
                          val coreAccount = Account(/* map parsedInfo to Account */)
                          activeGoogleAccountHolder.setActiveAccountId(coreAccount.id) // if applicable
                          googleAuthResultChannel.trySend(GenericAuthResult.Success(coreAccount))
                      } catch (e: Exception) {
                          googleAuthResultChannel.trySend(GenericAuthResult.Error("Token exchange failed: ${e.message}", GenericAuthErrorType.AUTHENTICATION_FAILED))
                      }
                  } else if (authException != null) {
                       if (authException.type == AuthorizationException.TYPE_GENERAL_ERROR && authException.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) {
                          googleAuthResultChannel.trySend(GenericAuthResult.Cancelled)
                       } else {
                          googleAuthResultChannel.trySend(GenericAuthResult.Error(authException.errorDescription ?: "Google auth failed", GenericAuthErrorType.AUTHENTICATION_FAILED))
                       }
                  } else { // Typically user cancellation if both are null but result is OK
                      googleAuthResultChannel.trySend(GenericAuthResult.Cancelled) // Or a specific error
                  }
              } else { // RESULT_CANCELED or other issues
                   googleAuthResultChannel.trySend(GenericAuthResult.Cancelled) // Or a specific error
              }
          } else {
              // For MS, if AccountRepository.handleAuthenticationResult is called, it could delegate.
              // However, MSAL typically handles its own activity results internally.
              // The new signIn flow for MS should make this method less relevant for MS.
               microsoftAccountRepository.handleAuthenticationResult(providerType, resultCode, data) // if MS repo still needs it
          }
      }
      
      // Illustrative signOut for Google
      override fun signOut(account: Account): Flow<GenericSignOutResult> {
          if (account.providerType.equals(Account.PROVIDER_TYPE_GOOGLE, ignoreCase = true)) {
              return flow {
                  try {
                      // Existing logic: revoke token, clear persistence
                      val authState = googleTokenPersistenceService.getAuthState(account.id)
                      if (authState?.refreshToken != null) {
                          appAuthHelperService.revokeToken(authState.refreshToken!!) // handle success/failure
                      }
                      googleTokenPersistenceService.clearTokens(account.id, removeAccount = true)
                      _googleAccounts.value = _googleAccounts.value.filterNot { it.id == account.id }
                       if (activeGoogleAccountHolder.getActiveAccountIdValue() == account.id) {
                          activeGoogleAccountHolder.clearActiveAccountId()
                          // Potentially set next active Google account
                      }
                      updateCombinedAccountsAndOverallAuthState()
                      emit(GenericSignOutResult.Success)
                  } catch (e: Exception) {
                      Timber.e(e, "Google sign out failed for ${account.username}")
                      emit(GenericSignOutResult.Error("Google sign out failed: ${e.message}", GenericAuthErrorType.UNKNOWN_ERROR))
                  }
              }
          } else if (account.providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
              return microsoftAccountRepository.signOut(account)
                  .onEach { result -> if (result is GenericSignOutResult.Success) updateCombinedAccountsAndOverallAuthState() }
          }
          return flowOf(GenericSignOutResult.Error("Unsupported provider for sign out", GenericAuthErrorType.INVALID_REQUEST))
      }

      // updateCombinedAccountsAndOverallAuthState() should be called after successful sign-in/out
      // to refresh the exposed combined list and overall state.
      ```

**Phase 5: Update `:app` Module (ViewModels & UI)**

1. **ViewModels (e.g., `MainViewModel.kt`):**
    * **Action:** Update to call the modified `AccountRepository` methods (`signIn`, `signOut`) and
      handle `GenericAuthResult` / `GenericSignOutResult`.
    * **Key Changes for `signIn`:**
        * When `signIn` is called, the ViewModel will collect the `Flow<GenericAuthResult>`.
        * If `GenericAuthResult.UiActionRequired(intent)` is emitted (for Google):
            * The ViewModel will expose this `Intent` to the UI (Activity/Fragment).
            * The UI launches the `Intent` for result.
            * The Activity/Fragment's `onActivityResult` will call
              `accountRepository.handleAuthenticationResult(...)`.
            * The original `signIn` Flow collection in the ViewModel will then receive the
              subsequent `GenericAuthResult.Success/Error/Cancelled` from the repository.
        * If `GenericAuthResult.Success`, `Error`, or `Cancelled` is emitted directly (for MS, or
          after Google's UI step):
            * The ViewModel updates its UI state accordingly (e.g., navigate, show error message,
              clear loading).
    * **Illustrative ViewModel handling for `signIn`:**
      ```kotlin
      // In MainViewModel.kt
      // private val _pendingAuthIntent = MutableStateFlow<Intent?>(null)
      // val pendingAuthIntent: StateFlow<Intent?> = _pendingAuthIntent.asStateFlow()

      fun startSignInProcess(activity: Activity, providerType: String, loginHint: String? = null) {
          viewModelScope.launch {
              _uiState.update { it.copy(isLoadingAccountAction = true) }
              defaultAccountRepository.signIn(activity, loginHint, providerType)
                  .collect { result ->
                      _uiState.update { it.copy(isLoadingAccountAction = false) } // Stop loading once a definitive result or UI action is posted
                      when (result) {
                          is GenericAuthResult.Success -> {
                              _uiState.update { it.copy(toastMessage = "Signed in as ${result.account.username}") }
                              // Navigation or further UI updates
                          }
                          is GenericAuthResult.UiActionRequired -> {
                              _uiState.update { it.copy(isLoadingAccountAction = true) } // Or a specific "waiting for user action" state
                              _pendingAuthIntent.value = result.intent // Activity observes this to launch
                          }
                          is GenericAuthResult.Error -> {
                              _uiState.update { ui ->
                                  ui.copy(toastMessage = "Error: ${result.message}")
                                  // if (result.msalRequiresInteractiveSignIn) { /* Specific UI to guide re-auth for MSAL */ }
                              }
                          }
                          is GenericAuthResult.Cancelled -> {
                              _uiState.update { it.copy(toastMessage = "Sign-in cancelled.") }
                          }
                      }
                  }
          }
      }

      fun authIntentLaunched() {
          _pendingAuthIntent.value = null
      }

      // Activity calls this after onActivityResult
      fun handleAuthenticationResult(providerType: String, resultCode: Int, data: Intent?) {
           viewModelScope.launch {
              // isLoadingAccountAction should already be true if we are expecting this result for Google.
              // Or, DefaultAccountRepository's handleAuthenticationResult will unblock the signIn flow.
              defaultAccountRepository.handleAuthenticationResult(providerType, resultCode, data)
              // The original signIn flow collector will receive the final result.
          }
      }
      ```
    * **Key changes for `signOut`:**
        * ViewModel calls `accountRepository.signOut(account)`.
        * Collects `Flow<GenericSignOutResult>` and updates UI (toast, clear active user, etc.).

**Phase 6: Change Gradle Dependency**

1. **File:** `data/build.gradle.kts`
    * **Action:** Modify the dependency.
    * **Change:** `api(project(":backend-microsoft"))` to
      `implementation(project(":backend-microsoft"))`. (This remains the same and is the primary
      goal).

**Phase 7: Testing and Validation**

1. **Unit Tests:** Update/create tests for `MicrosoftAccountRepository`,
   `DefaultAccountRepository` (especially the Google `signIn` flow logic), `MicrosoftErrorMapper`,
   and ViewModels to mock/verify interactions with the new generic types and flows.
2. **Integration/Manual Testing:**
    * Thoroughly test Microsoft sign-in/out scenarios (success, cancellation, various errors
      including network and `MsalUiRequiredException` leading to `msalRequiresInteractiveSignIn`
      flag).
    * Thoroughly test Google sign-in/out scenarios (success, cancellation, errors during intent
      launch, token exchange failures). Ensure the `UiActionRequired` and subsequent result delivery
      work correctly.

**Impact Assessment & Potential Soft Spots (Revisited):**

* **Main Impact:** `:app` ViewModels and UI logic for handling authentication, especially Google's
  multi-step process.
* **Google Flow Complexity:** The `callbackFlow` and `Channel` (or alternative synchronization)
  mechanism in `DefaultAccountRepository` for Google sign-in is intricate. It needs careful
  implementation and testing to ensure robustness and prevent leaks or race conditions. Consider
  using unique request IDs if concurrent Google sign-ins were possible (though unlikely for a
  typical app).
* **Error Mapping Robustness:** `MicrosoftErrorMapper` and error handling in
  `DefaultAccountRepository` for Google path need to be comprehensive in categorizing exceptions
  into `GenericAuthErrorType` and user-friendly messages.
* **Transitive Dependencies:** A clean build after the Gradle change is crucial.
* **`loginHint`**: The `loginHint` parameter has been added to `signIn`. Ensure it's correctly
  passed down to `MicrosoftAuthManager` and `AppAuthHelperService`.

This updated plan provides a more detailed approach, especially for the Google sign-in flow, aiming
for a consistent `AccountRepository` interface. The introduction of
`GenericAuthResult.UiActionRequired` makes the Google flow more explicit for the ViewModel.

This plan focuses on the core task of introducing the mapping layer for MSAL types. The alignment of
the Google/AppAuth flow to the new generic result types in `AccountRepository` is a significant
follow-up consideration for API consistency. 