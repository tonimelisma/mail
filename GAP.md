# Gap Analysis: Authentication Refactoring

## 1. Overall Vision & Objectives

The primary vision is to establish a robust, unified, and maintainable authentication architecture
for the application, supporting both Microsoft and Google identity providers. This architecture will
seamlessly integrate with Ktor for authenticated API calls.

**Key Objectives:**

* **Unified Token Provision for Ktor:** Implement a common `TokenProvider` interface (
  `core-data.auth.TokenProvider`) that both `MicrosoftKtorTokenProvider` and
  `GoogleKtorTokenProvider` will adhere to. This interface will be responsible for supplying
  `BearerTokens` to Ktor's authentication plugin.
* **Clear Separation of Concerns:**
    * **`AuthManager` (`MicrosoftAuthManager`, `GoogleAuthManager`):** Handle all provider-specific
      SDK interactions (MSAL for Microsoft, AppAuth for Google), including user sign-in/sign-out,
      consent flows, and secure persistence of tokens and account details.
    * **`KtorTokenProvider` (`MicrosoftKtorTokenProvider`, `GoogleKtorTokenProvider`):** Act as a
      bridge between the `AuthManager` and Ktor. Responsible for loading current tokens, attempting
      silent refresh, and converting provider-specific tokens/errors into `BearerTokens` and
      standardized exceptions (`NeedsReauthenticationException`, `TokenProviderException`).
    * **`UserPrincipal`:** A common data class (`core-data.auth.UserPrincipal`) representing an
      authenticated user within Ktor's pipeline, populated with essential details like account ID,
      email, and provider type.
    * **`AccountRepository` (`DefaultAccountRepository`):** Centralized management of account data,
      including marking accounts for re-authentication.
    * **`ActiveAccountHolder` (`ActiveMicrosoftAccountHolder`, `ActiveGoogleAccountHolder`):** Track
      the currently active account for each provider, ensuring that Ktor providers attempt to get
      tokens for the correct user.
* **Robust Error Handling:** Implement consistent error handling, distinguishing between recoverable
  errors (e.g., token expired, refresh possible) and errors requiring user intervention (e.g.,
  re-authentication needed).
* **Feature Parity:** Ensure both Microsoft and Google authentication flows are fully functional,
  supporting silent token acquisition, refresh, and re-authentication prompts when necessary.
* **Eliminate Redundancy:** Remove any outdated or duplicative authentication logic, particularly
  addressing the concerns around `GoogleAuthManager.signInInteractive` and ensuring the Google flow
  relies on AppAuth.
* **Build Stability:** Achieve a stable build where all authentication components compile and
  integrate correctly.
* **Maintainability & Testability:** Structure the code in a way that is easy to understand,
  maintain, and test. (Testing itself is out of scope for this immediate plan but the design should
  facilitate it).

This refactoring aims to resolve existing build issues, clarify the authentication flow, and provide
a solid foundation for future development.

## 2. Core Authentication Components (Status & Plan)

These components form the shared foundation for authentication across different providers.

* **`core-data/src/main/java/net/melisma/core_data/auth/TokenProvider.kt`**
    * **Status:** `CREATED` (as per user confirmation and previous steps).
    * **Purpose:** Defines the contract for Ktor token providers (`getBearerTokens`,
      `refreshBearerTokens`).
    * **Work Outstanding:** None on the interface itself.

* **`core-data/src/main/java/net/melisma/core_data/auth/UserPrincipal.kt`**
    * **Status:** `CREATED` (as per user confirmation and previous steps).
    * **Purpose:** Data class representing the authenticated user within Ktor (`accountId`, `email`,
      `displayName`, `providerType`).
    * **Work Outstanding:** None on the data class itself.

* **`core-data/src/main/java/net/melisma/core_data/auth/TokenProviderException.kt`**
    * **Status:** `NEEDS EXPLICIT CREATION/VERIFICATION` (though used by
      `MicrosoftKtorTokenProvider`).
    * **Purpose:** Custom exception for general errors during token provider operations.
    * **Detailed Tasks:**
        1. **Verify or Create File:** Check if
           `core-data/src/main/java/net/melisma/core_data/auth/TokenProviderException.kt` exists.
        2. **Define Class:** If not present or incomplete, define it:
           ```kotlin
           package net.melisma.core_data.auth

           class TokenProviderException(
               override val message: String,
               override val cause: Throwable? = null
           ) : RuntimeException(message, cause)
           ```
        3. **Usage:** Ensure it's used consistently by both `MicrosoftKtorTokenProvider` and the new
           `GoogleKtorTokenProvider` for errors not covered by `NeedsReauthenticationException`.

* **`core-data/src/main/java/net/melisma/core_data/auth/NeedsReauthenticationException.kt`**
    * **Status:** `NEEDS EXPLICIT CREATION/VERIFICATION` (though used by
      `MicrosoftKtorTokenProvider`).
    * **Purpose:** Custom exception to signal that user interaction is required to re-authenticate.
    * **Detailed Tasks:**
        1. **Verify or Create File:** Check if
           `core-data/src/main/java/net/melisma/core_data/auth/NeedsReauthenticationException.kt`
           exists.
        2. **Define Class:** If not present or incomplete, define it to include
           `accountIdToReauthenticate`:
           ```kotlin
           package net.melisma.core_data.auth

           class NeedsReauthenticationException(
               val accountIdToReauthenticate: String?,
               override val message: String,
               override val cause: Throwable? = null
           ) : RuntimeException(message, cause)
           ```
        3. **Usage:** Ensure it's thrown by both token providers when their respective auth
           managers (`acquireTokenSilent` for MSAL, `getFreshAccessToken` for Google/AppAuth)
           indicate that UI is required or re-login is necessary. The `accountIdToReauthenticate`
           should be populated.

## 3. Microsoft Backend (`:backend-microsoft`)

* **`MicrosoftKtorTokenProvider.kt`**
    * **Status:** `LARGELY COMPLETE` (as per user-provided version and review).
    * **Implementation Details:**
        * Implements `TokenProvider`.
        * Uses `microsoftAuthManager.acquireTokenSilent`.
        * Handles `AcquireTokenResult` (Success, UiRequired, Error).
        * Stores MSAL `IAccount.id` in `BearerTokens.refreshToken` field for later retrieval in
          Ktor's `validate` block.
        * Throws `NeedsReauthenticationException` and `TokenProviderException`.
        * Calls `accountRepository.markAccountForReauthentication`.
        * Uses `ActiveMicrosoftAccountHolder`.
        * Includes a mutex for token refresh.
    * **Work Outstanding:** Minor cleanup (e.g., unused imports). Confirm `Timber` is the
      project-wide logging solution or adjust.

* **Ktor Integration (DI Module, e.g., `BackendMicrosoftModule.kt`)**
    * **Status:** `TODO`
    * **Objective:** Configure Ktor's `Authentication` plugin to use `MicrosoftKtorTokenProvider`.
    * **Detailed Tasks:**
        1. **Locate or Create DI Module:**
            * Identify the Hilt/Koin module responsible for Ktor client setup for the Microsoft
              backend (e.g., `BackendMicrosoftModule.kt` or a shared `NetworkModule.kt`). If it
              doesn't exist, create it.
        2. **Define Named Ktor `bearer` Authentication Provider:**
            * Inside the Ktor `HttpClient` configuration block, install the `Auth` feature if not
              already present.
            * Add a `bearer` authentication provider with a unique name, for example,
              `MS_AUTH_PROVIDER_NAME = "msAuth"`.
              ```kotlin
              // In your DI module (e.g., BackendMicrosoftModule.kt)
              // @Provides or single { } block for Ktor HttpClient
              // ...
              install(Auth) {
                  bearer(MS_AUTH_PROVIDER_NAME) {
                      // Configuration steps below
                  }
              }
              // ...
              ```
        3. **Implement `loadTokens` Lambda:**
            * This lambda is called by Ktor when a request requiring auth is made and no tokens are
              cached (or they are invalid).
            * Inject/obtain an instance of `MicrosoftKtorTokenProvider`.
            * Call `microsoftKtorTokenProvider.getBearerTokens()`.
            * **Return Value:** `BearerTokens?`
              ```kotlin
              // Inside bearer(MS_AUTH_PROVIDER_NAME) { ... }
              loadTokens {
                  // Obtain microsoftKtorTokenProvider (e.g., from DI if configuring client here,
                  // or pass it if this block is in a function)
                  val provider: MicrosoftKtorTokenProvider = get() // Example for Koin
                  Timber.tag("KtorAuth").d("MS Auth: loadTokens called.")
                  try {
                      provider.getBearerTokens()
                  } catch (e: NeedsReauthenticationException) {
                      Timber.tag("KtorAuth").w(e, "MS Auth: Needs re-authentication during loadTokens.")
                      // Ktor will typically see a null here and the request will fail.
                      // The exception should have already triggered marking the account for re-auth.
                      null // Or rethrow if Ktor/your setup should handle it further up
                  } catch (e: TokenProviderException) {
                      Timber.tag("KtorAuth").e(e, "MS Auth: TokenProviderException during loadTokens.")
                      null // Or rethrow
                  }
              }
              ```
        4. **Implement `refreshTokens` Lambda:**
            * This lambda is called by Ktor when a request receives a 401 Unauthorized response.
            * The `oldTokens: BearerTokens?` parameter will be the tokens that failed.
            * Inject/obtain an instance of `MicrosoftKtorTokenProvider`.
            * Call `microsoftKtorTokenProvider.refreshBearerTokens(oldTokens)`.
            * **Return Value:** `BearerTokens?` (the new tokens if refresh was successful).
              ```kotlin
              // Inside bearer(MS_AUTH_PROVIDER_NAME) { ... }
              refreshTokens { oldTokens ->
                  // Obtain microsoftKtorTokenProvider
                  val provider: MicrosoftKtorTokenProvider = get() // Example for Koin
                  Timber.tag("KtorAuth").d("MS Auth: refreshTokens called.")
                  try {
                      provider.refreshBearerTokens(oldTokens)
                  } catch (e: NeedsReauthenticationException) {
                      Timber.tag("KtorAuth").w(e, "MS Auth: Needs re-authentication during refreshTokens.")
                      null
                  } catch (e: TokenProviderException) {
                      Timber.tag("KtorAuth").e(e, "MS Auth: TokenProviderException during refreshTokens.")
                      null
                  }
              }
              ```
        5. **Implement `validate` Lambda:**
            * This lambda is called by Ktor after tokens are successfully loaded or refreshed. Its
              job is to convert the `BearerTokens` (specifically the `accountId` we stored) into a
              `Principal` object (our `UserPrincipal`).
            * The `credentials: BearerTokens` parameter contains the tokens.
            * Inject/obtain an instance of `MicrosoftAuthManager`.
            * Retrieve the `accountId` from `credentials.refreshToken` (as per
              `MicrosoftKtorTokenProvider`'s implementation where we store `IAccount.id`).
            * If `accountId` is null or empty, log an error and return `null`.
            * Call `microsoftAuthManager.getAccount(accountId)` to fetch the
              `ManagedMicrosoftAccount`.
            * If the `ManagedMicrosoftAccount` is found:
                * Construct a `UserPrincipal` using details from it (e.g., `id`, `email`,
                  `displayName`, and `Account.PROVIDER_TYPE_MS`).
                * Return the populated `UserPrincipal`.
            * If the `ManagedMicrosoftAccount` is not found (e.g., account deleted, or ID mismatch),
              log an error and return `null`. This will typically cause the request to fail as
              unauthenticated.
            * **Return Value:** `Principal?` (our `UserPrincipal` instance or `null`).
              ```kotlin
              // Inside bearer(MS_AUTH_PROVIDER_NAME) { ... }
              validate { credentials ->
                  // Obtain microsoftAuthManager
                  val authManager: MicrosoftAuthManager = get() // Example for Koin
                  Timber.tag("KtorAuth").d("MS Auth: validate called. Credentials refreshToken (used as accountId): ${credentials.refreshToken}")
                  val accountId = credentials.refreshToken
                  if (accountId.isNullOrBlank()) {
                      Timber.tag("KtorAuth").e("MS Auth: Account ID is null or blank in BearerTokens during validation.")
                      null
                  } else {
                      try {
                          val managedAccount = authManager.getAccount(accountId)
                          if (managedAccount != null) {
                              Timber.tag("KtorAuth").d("MS Auth: Account found for validation: ${managedAccount.email}")
                              UserPrincipal(
                                  accountId = managedAccount.id,
                                  email = managedAccount.email,
                                  displayName = managedAccount.displayName,
                                  providerType = Account.PROVIDER_TYPE_MS
                              )
                          } else {
                              Timber.tag("KtorAuth").w("MS Auth: No ManagedMicrosoftAccount found for ID: $accountId during validation.")
                              null
                          }
                      } catch (e: Exception) {
                          Timber.tag("KtorAuth").e(e, "MS Auth: Exception during validation logic for accountId: $accountId")
                          null
                      }
                  }
              }
              ```
        6. **Ensure Necessary Imports:**
            * `io.ktor.client.plugins.auth.*`
            * `io.ktor.client.plugins.auth.providers.*`
            * `net.melisma.core_data.auth.UserPrincipal`
            * `net.melisma.core_data.model.Account`
            * `net.melisma.backend_microsoft.auth.MicrosoftKtorTokenProvider`
            * `net.melisma.backend_microsoft.auth.MicrosoftAuthManager`
            * `timber.log.Timber` (or your logging framework)
            * Your DI framework's `get()` or equivalent.

* **`MicrosoftAuthManager.kt`**
    * **Status:** `FUNCTIONAL` for `MicrosoftKtorTokenProvider`'s needs.
    * **Key Methods Used:** `acquireTokenSilent(IAccount, scopes)`, `getAccount(accountId)`.
    * **Work Outstanding:** None currently identified for this phase, assuming its existing MSAL
      interactions and token persistence are robust.

* **`ActiveMicrosoftAccountHolder.kt`**
    * **Status:** `ASSUMED FUNCTIONAL`.
    * **Purpose:** Provides the active Microsoft account ID to `MicrosoftKtorTokenProvider`.
    * **Work Outstanding:** None, assuming it correctly reflects the active user state.

## 4. Google Backend (`:backend-google`)

This backend requires more significant work to align with the new architecture.

* **`GoogleKtorTokenProvider.kt`**
    * **Status:** `TODO - MAJOR REFACTOR/REWRITE`.
    * **Objective:** Implement `TokenProvider` for Google, using `GoogleAuthManager` and AppAuth
      mechanisms.
    * **Detailed Tasks:**
        1. **File Location:**
           `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleKtorTokenProvider.kt`
        2. **Class Definition and Injections:**
            * Make the class implement `net.melisma.core_data.auth.TokenProvider`.
            * Inject `GoogleAuthManager`, `ActiveGoogleAccountHolder`, and `AccountRepository` via
              constructor (e.g., using Hilt's `@Inject` or Koin).
            * Add a `TAG` for logging (e.g., `private val TAG = "GoogleKtorTokenProv"`).
            * Consider adding a `Mutex` for `refreshTokens` if deemed necessary, similar to
              `MicrosoftKtorTokenProvider`.
              ```kotlin
              package net.melisma.backend_google.auth

              import kotlinx.coroutines.flow.first
              import kotlinx.coroutines.sync.Mutex
              import kotlinx.coroutines.sync.withLock // If using mutex
              import net.melisma.core_data.auth.* 
              import net.melisma.core_data.model.Account
              import net.melisma.core_data.repository.AccountRepository
              import net.melisma.backend_google.manager.GoogleAuthManager // Assuming this is the correct package for GoogleAuthManager
              import net.melisma.backend_google.model.GetFreshAccessTokenResult // Assuming this is the correct package
              import io.ktor.client.plugins.auth.providers.BearerTokens
              import timber.log.Timber
              import javax.inject.Inject
              import javax.inject.Singleton

              @Singleton
              class GoogleKtorTokenProvider @Inject constructor(
                  private val googleAuthManager: GoogleAuthManager,
                  private val activeAccountHolder: ActiveGoogleAccountHolder,
                  private val accountRepository: AccountRepository
              ) : TokenProvider {
                  private val TAG = "GoogleKtorTokenProv"
                  private val refreshMutex = Mutex() // Optional: if refresh needs explicit locking

                  // TODO: Implement methods
              }
              ```
        3. **Implement `getBearerTokens(): BearerTokens?`:**
            * Log the method call.
            * Get the active Google account ID:
              `val accountId = activeAccountHolder.getActiveGoogleAccountIdValue()`.
            * If `accountId` is null: Log a warning, return `null` (Ktor will likely fail the
              request).
            * Call `val tokenResult = googleAuthManager.getFreshAccessToken(accountId).first()`.
            * Use a `when` expression to handle the `GetFreshAccessTokenResult` sealed class:
                * `is GetFreshAccessTokenResult.Success`:
                    * Log success.
                    * Extract `accessToken = tokenResult.accessToken`.
                    * Return `BearerTokens(accessToken = accessToken, refreshToken = accountId)`. (
                      Store `accountId` in `refreshToken` for consistency).
                * `is GetFreshAccessTokenResult.NeedsReLogin`:
                    * Log a warning.
                    * Call
                      `accountRepository.markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_GOOGLE)`.
                    * Throw
                      `NeedsReauthenticationException(accountIdToReauthenticate = accountId, message = "Google account needs re-login: ${tokenResult.error?.message}", cause = tokenResult.error)`.
                * `is GetFreshAccessTokenResult.Error`:
                    * Log an error.
                    * Throw
                      `TokenProviderException(message = "Error getting fresh Google token: ${tokenResult.error.message}", cause = tokenResult.error)`.
                * `is GetFreshAccessTokenResult.NoAccount`,
                  `is GetFreshAccessTokenResult.NotInitialized` (and any other specific error/state
                  types):
                    * Log a warning/error.
                    * Throw
                      `TokenProviderException(message = "Failed to get Google token: ${tokenResult::class.java.simpleName}")`.
            * Wrap the call to `googleAuthManager.getFreshAccessToken` and the `when` block in a
              `try-catch` for unexpected exceptions, rethrowing them as `TokenProviderException` if
              necessary.
        4. **Implement `refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens?`:**
            * Log the method call.
            * Determine `accountId`:
                * Start with
                  `val currentAccountId = activeAccountHolder.getActiveGoogleAccountIdValue()`.
                * If `currentAccountId` is null, try
                  `val oldAccountIdFromToken = oldTokens?.refreshToken`.
                * Use `currentAccountId ?: oldAccountIdFromToken`.
            * If the final `accountId` is null: Log an error, return `null`.
            * **Logic:** The core logic will be very similar to `getBearerTokens()`.
                * Call `googleAuthManager.getFreshAccessToken(accountId).first()`.
                * Handle the `GetFreshAccessTokenResult` exactly as in `getBearerTokens()` (Success,
                  NeedsReLogin, Error, etc.), returning `BearerTokens` or throwing the appropriate
                  exceptions.
            * **Mutex (Optional but Recommended):** Wrap the token acquisition logic with
              `refreshMutex.withLock { ... }` if you added the mutex to prevent concurrent refresh
              attempts for the same account, similar to `MicrosoftKtorTokenProvider`.

* **Ktor Integration (DI Module, e.g., `BackendGoogleModule.kt`)**
    * **Status:** `TODO`
    * **Objective:** Configure Ktor's `Authentication` plugin to use `GoogleKtorTokenProvider`.
    * **Detailed Tasks:**
        1. **Locate or Create DI Module:**
            * Identify the Hilt/Koin module for Ktor client setup for the Google backend (e.g.,
              `BackendGoogleModule.kt` or a shared `NetworkModule.kt`). If it doesn't exist, create
              it.
        2. **Define Named Ktor `bearer` Authentication Provider:**
            * Inside the Ktor `HttpClient` configuration block, install the `Auth` feature if not
              already present.
            * Add a `bearer` authentication provider with a unique name, for example,
              `GOOGLE_AUTH_PROVIDER_NAME = "googleAuth"`.
              ```kotlin
              // In your DI module (e.g., BackendGoogleModule.kt)
              // @Provides or single { } block for Ktor HttpClient
              // ...
              install(Auth) {
                  bearer(GOOGLE_AUTH_PROVIDER_NAME) {
                      // Configuration steps below
                  }
              }
              // ...
              ```
        3. **Implement `loadTokens` Lambda:**
            * Inject/obtain an instance of `GoogleKtorTokenProvider`.
            * Call `googleKtorTokenProvider.getBearerTokens()`.
            * **Return Value:** `BearerTokens?`
              ```kotlin
              // Inside bearer(GOOGLE_AUTH_PROVIDER_NAME) { ... }
              loadTokens {
                  val provider: GoogleKtorTokenProvider = get() // Example for Koin
                  Timber.tag("KtorAuth").d("Google Auth: loadTokens called.")
                  try {
                      provider.getBearerTokens()
                  } catch (e: NeedsReauthenticationException) {
                      Timber.tag("KtorAuth").w(e, "Google Auth: Needs re-authentication during loadTokens.")
                      null
                  } catch (e: TokenProviderException) {
                      Timber.tag("KtorAuth").e(e, "Google Auth: TokenProviderException during loadTokens.")
                      null
                  }
              }
              ```
        4. **Implement `refreshTokens` Lambda:**
            * The `oldTokens: BearerTokens?` parameter will be the tokens that failed.
            * Inject/obtain an instance of `GoogleKtorTokenProvider`.
            * Call `googleKtorTokenProvider.refreshBearerTokens(oldTokens)`.
            * **Return Value:** `BearerTokens?` (the new tokens if refresh was successful).
              ```kotlin
              // Inside bearer(GOOGLE_AUTH_PROVIDER_NAME) { ... }
              refreshTokens { oldTokens ->
                  val provider: GoogleKtorTokenProvider = get() // Example for Koin
                  Timber.tag("KtorAuth").d("Google Auth: refreshTokens called.")
                  try {
                      provider.refreshBearerTokens(oldTokens)
                  } catch (e: NeedsReauthenticationException) {
                      Timber.tag("KtorAuth").w(e, "Google Auth: Needs re-authentication during refreshTokens.")
                      null
                  } catch (e: TokenProviderException) {
                      Timber.tag("KtorAuth").e(e, "Google Auth: TokenProviderException during refreshTokens.")
                      null
                  }
              }
              ```
        5. **Implement `validate` Lambda:**
            * The `credentials: BearerTokens` parameter contains the tokens.
            * Inject/obtain an instance of `GoogleAuthManager`.
            * Retrieve the `accountId` from `credentials.refreshToken`.
            * If `accountId` is null or empty, log an error and return `null`.
            * Call `googleAuthManager.getAccount(accountId)` to fetch the `ManagedGoogleAccount`.
            * If the `ManagedGoogleAccount` is found:
                * Construct a `UserPrincipal` using details from it (e.g., `id`, `email`,
                  `displayName`, and `Account.PROVIDER_TYPE_GOOGLE`).
                * Return the populated `UserPrincipal`.
            * If the `ManagedGoogleAccount` is not found, log an error and return `null`.
            * **Return Value:** `Principal?` (our `UserPrincipal` instance or `null`).
              ```kotlin
              // Inside bearer(GOOGLE_AUTH_PROVIDER_NAME) { ... }
              validate { credentials ->
                  val authManager: GoogleAuthManager = get() // Example for Koin
                  Timber.tag("KtorAuth").d("Google Auth: validate called. Credentials refreshToken (as accountId): ${credentials.refreshToken}")
                  val accountId = credentials.refreshToken
                  if (accountId.isNullOrBlank()) {
                      Timber.tag("KtorAuth").e("Google Auth: Account ID is null or blank in BearerTokens during validation.")
                      null
                  } else {
                      try {
                          val managedAccount = authManager.getAccount(accountId) // Assuming getAccount exists and returns ManagedGoogleAccount?
                          if (managedAccount != null) {
                              Timber.tag("KtorAuth").d("Google Auth: Account found for validation: ${managedAccount.email}")
                              UserPrincipal(
                                  accountId = managedAccount.id,
                                  email = managedAccount.email,
                                  displayName = managedAccount.profile?.displayName, // Adjust based on ManagedGoogleAccount structure
                                  providerType = Account.PROVIDER_TYPE_GOOGLE
                              )
                          } else {
                              Timber.tag("KtorAuth").w("Google Auth: No ManagedGoogleAccount found for ID: $accountId during validation.")
                              null
                          }
                      } catch (e: Exception) {
                          Timber.tag("KtorAuth").e(e, "Google Auth: Exception during validation logic for accountId: $accountId")
                          null
                      }
                  }
              }
              ```
        6. **Ensure Necessary Imports:**
            * `io.ktor.client.plugins.auth.*`
            * `io.ktor.client.plugins.auth.providers.*`
            * `net.melisma.core_data.auth.UserPrincipal`
            * `net.melisma.core_data.model.Account`
            * `net.melisma.backend_google.auth.GoogleKtorTokenProvider`
            * `net.melisma.backend_google.manager.GoogleAuthManager` // or actual package
            * `timber.log.Timber` (or your logging framework)
            * Your DI framework's `get()` or equivalent.

* **`GoogleAuthManager.kt`**
    * **Status:** `REVIEW & REFINE`.
    * **Objective:** Ensure `GoogleAuthManager` aligns with the AppAuth-centric flow and supports
      the needs of `GoogleKtorTokenProvider` and Ktor integration.
    * **Detailed Key Areas for Review/Refinement:**
        1. **`getFreshAccessToken(accountId: String): Flow<GetFreshAccessTokenResult>` (CRITICAL):**
            * **Current Implementation Check:** Verify how this method currently obtains a fresh
              access token. It *must* use AppAuth's `AuthState.performActionWithFreshTokens` or an
              equivalent mechanism that correctly handles the stored refresh token to get a new
              access token without user interaction if possible.
            * **AppAuth `AuthState`:**
                * Ensure `AuthState` is loaded correctly for the given `accountId` before calling
                  `performActionWithFreshTokens`.
                * Ensure the `AuthState` is updated and persisted after a successful token refresh (
                  AppAuth usually handles this if `performActionWithFreshTokens` is used correctly
                  with an `AuthStateChangeNotifier`).
            * **`GetFreshAccessTokenResult` Sealed Class:**
                * `Success(accessToken: String)`: Ensure this is emitted with a valid, new access
                  token.
                * `NeedsReLogin(error: Throwable?)`: This should be emitted if
                  `performActionWithFreshTokens` indicates that the refresh token is
                  invalid/expired, or if any other error occurs that requires the user to sign in
                  again (e.g., `AuthorizationException` that is not recoverable).
                * `Error(error: Throwable)`: For other unexpected errors during the process.
                * `NoAccount`, `NotInitialized`: Confirm these states are handled and emitted if
                  `accountId` is invalid or AppAuth isn't ready.
            * **Error Wrapping:** Ensure that exceptions from AppAuth (e.g.,
              `AuthorizationException`) are caught and appropriately mapped to `NeedsReLogin` or
              `Error` variants of `GetFreshAccessTokenResult`.
        2. **Review/Remove `signInInteractive` method (and related flows):**
            * **Identify Purpose:** Understand what `signInInteractive` was originally intended for.
            * **AppAuth Standard Flow:** The primary sign-in flow should be initiated by the UI
              layer by creating an `AuthorizationRequest` and launching the `PendingIntent` obtained
              from `AuthorizationService.getAuthorizationRequestIntent(...)`.
            * **Handling `AuthorizationResponse`/`AuthorizationException`:** `GoogleAuthManager`
              should have a method (e.g., `handleAuthorizationResponse(intent: Intent)`) that
              processes the result of the AppAuth redirect. This method will typically:
                * Extract `AuthorizationResponse` or `AuthorizationException` from the intent.
                * If `AuthorizationResponse`:
                    * Perform the token exchange request (`TokenRequest`) to get initial tokens.
                    * Create/update `AuthState`.
                    * Persist the `AuthState` and new `ManagedGoogleAccount` details.
                * If `AuthorizationException`: Handle the error (e.g., log, report to UI).
            * **Deprecation:** If `signInInteractive` duplicates the standard AppAuth initiation or
              tries to manage UI components directly, it should be removed. The `GoogleAuthManager`
              should not be responsible for starting UI intents directly in a way that bypasses
              AppAuth's recommended client-side flow.
        3. **`getAccount(accountId: String): Flow<ManagedGoogleAccount?>` (or suspend fun):**
            * **Data Source:** Verify this method correctly retrieves the `ManagedGoogleAccount` (
              containing user ID, email, display name, etc.) from its persistent store (e.g.,
              database, SharedPreferences via `AccountRepository` or internal persistence).
            * **Consistency:** Ensure the data used to create `ManagedGoogleAccount` (especially
              `email` and `displayName`) is populated after a successful AppAuth flow and token
              exchange, typically from the ID token or userinfo endpoint if called.
        4. **AppAuth `AuthState` Persistence:**
            * **Mechanism:** Confirm how `AuthState` objects (which contain the actual tokens,
              including refresh token) are being serialized (e.g., to JSON) and persisted securely
              for each Google account.
            * **Loading:** Ensure `AuthState` is correctly loaded when needed (e.g., before calling
              `performActionWithFreshTokens`).
        5. **User Info Endpoint Calls (Optional but common):**
            * Determine if user profile information (email, name) is fetched from the ID token (if
              available and sufficient) or via a separate call to the Google userinfo endpoint after
              obtaining tokens.
            * If using the userinfo endpoint, ensure this call is made with a valid access token and
              its result is used to populate/update the `ManagedGoogleAccount`.
        6. **Sign-Out Logic (`signOut(accountId: String)`):**
            * Ensure sign-out clears the persisted `AuthState` for the account.
            * Consider if it needs to interact with AppAuth for browser session cleanup (e.g.,
              redirect to `end_session_endpoint`, though this is more complex and often handled by
              clearing local tokens).
            * Clear the associated `ManagedGoogleAccount` from persistence.

* **`ActiveGoogleAccountHolder.kt`**
    * **Status:** `ASSUMED FUNCTIONAL`.
    * **Purpose:** Provides the active Google account ID.
    * **Work Outstanding:** None, assuming correct operation.

## 5. Data Layer (`:data`, `:core-data`)

* **`data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`**
    * **Status:** `FUNCTIONAL` for auth flow needs.
    * **Key Methods Used:**
        * `markAccountForReauthentication(accountId: String, providerType: String)`: Called by token
          providers when re-authentication is necessary.
        * `observeActiveAccount(providerType: String)`: Assumed to be used by `ActiveAccountHolder`
          implementations.
        * Methods to get/save account details (used by `AuthManager`s and Ktor `validate` lambdas
          via `AuthManager`s).
    * **Work Outstanding:** None identified for this phase.

* **Account Capabilities (e.g.,
  `core-data/src/main/java/net/melisma/core_data/repository/capabilities/GoogleAccountCapability.kt`)
  **
    * **Status:** `REVIEW`
    * **Objective:** Ensure provider-specific capabilities align with the Ktor-centric approach for
      API calls and don't duplicate Ktor client functionality unnecessarily.
    * **Considerations:** With authenticated Ktor clients, some direct capabilities for fetching
      data might become redundant if that data is now fetched via Ktor. However, capabilities for
      provider-specific actions not related to standard API calls (e.g., specific SDK functions,
      complex local data manipulations not suited for a simple Ktor call) might still be relevant.
    * **Detailed Tasks:**
        1. **List all Account Capabilities:** Identify all capability interfaces and their
           implementations in `:core-data` and provider-specific modules (e.g.,
           `GoogleAccountCapability`, `MicrosoftAccountCapability`).
        2. **For each capability method:**
            * **Analyze Purpose:** Understand what data it fetches or what operation it performs.
            * **Compare with Ktor Client:** Determine if the same data/operation can (or should) now
              be achieved via a Ktor client call using the authenticated `HttpClient`.
            * **Identify Redundancy:** If a capability method is purely for fetching data that a
              Ktor endpoint now provides, mark it for deprecation or removal.
            * **Identify Unique Value:** If a capability provides functionality beyond a simple API
              call (e.g., complex local data processing, interaction with a provider SDK for
              non-HTTP tasks, specific device integrations), it should likely be kept.
            * **Example - `GoogleAccountCapability.getEmails()`:** If this directly used a Google
              SDK to fetch emails, and now there's a Ktor client for a `GET /emails` endpoint, this
              capability method might be redundant. However, if it did complex local filtering
              *after* fetching, it might still have a role or its logic could be moved.
        3. **Refactor/Deprecate:**
            * For redundant capabilities, plan their deprecation path (e.g., `@Deprecated`
              annotation, update call sites to use Ktor client).
            * For capabilities that are kept, ensure they are still relevant and correctly integrate
              with the new auth model if they rely on account identity.
            * This task is less about immediate code changes and more about strategic alignment to
              avoid maintaining two ways of doing the same thing.

## 6. Summary of Work To Be Done (Detailed)

This section provides a more granular checklist based on the detailed tasks outlined in Sections
2-5.

**A. Core Authentication Components (Section 2):**

1. **`TokenProviderException.kt`:**
    *   [ ] Verify or create file
        `core-data/src/main/java/net/melisma/core_data/auth/TokenProviderException.kt`.
    *   [ ] Define class
        `TokenProviderException(override val message: String, override val cause: Throwable? = null) : RuntimeException(message, cause)`.
2. **`NeedsReauthenticationException.kt`:**
    *   [ ] Verify or create file
        `core-data/src/main/java/net/melisma/core_data/auth/NeedsReauthenticationException.kt`.
    *   [ ] Define class
        `NeedsReauthenticationException(val accountIdToReauthenticate: String?, override val message: String, override val cause: Throwable? = null) : RuntimeException(message, cause)`.

**B. Microsoft Backend (Section 3):**

1. **`MicrosoftKtorTokenProvider.kt` Minor Cleanup:**
    *   [ ] Remove unused imports.
    *   [ ] Confirm/adjust logging solution (e.g., ensure Timber is standard).
2. **Microsoft Ktor Integration (DI Module - e.g., `BackendMicrosoftModule.kt`):**
    *   [ ] Locate or create the relevant DI module.
    *   [ ] Define named Ktor `bearer` auth provider (e.g., `MS_AUTH_PROVIDER_NAME = "msAuth"`).
    *   [ ] Implement `loadTokens` lambda (call `microsoftKtorTokenProvider.getBearerTokens()`,
        handle exceptions).
    *   [ ] Implement `refreshTokens` lambda (call
        `microsoftKtorTokenProvider.refreshBearerTokens(oldTokens)`, handle exceptions).
    *   [ ] Implement `validate` lambda:
        *   [ ] Retrieve `accountId` from `credentials.refreshToken`.
        *   [ ] Call `microsoftAuthManager.getAccount(accountId)`.
        *   [ ] Construct and return `UserPrincipal` or `null`.
        *   [ ] Handle potential exceptions during validation.
    *   [ ] Ensure all necessary imports for Ktor auth setup.

**C. Google Backend (Section 4):**

1. **Refactor/Rewrite `GoogleKtorTokenProvider.kt`:**
    *   [ ] Ensure file exists at
        `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleKtorTokenProvider.kt`.
    *   [ ] Class definition: Implement `TokenProvider`, inject dependencies (`GoogleAuthManager`,
        `ActiveGoogleAccountHolder`, `AccountRepository`), add TAG, optional Mutex.
    *   [ ] Implement `getBearerTokens()` method:
        *   [ ] Get active Google account ID.
        *   [ ] Call `googleAuthManager.getFreshAccessToken(accountId).first()`.
        *   [ ] Handle `GetFreshAccessTokenResult` (Success, NeedsReLogin, Error, other states) -
            return `BearerTokens` or throw appropriate exceptions.
        *   [ ] Call `accountRepository.markAccountForReauthentication` on `NeedsReLogin`.
        *   [ ] Store `accountId` in `BearerTokens.refreshToken`.
        *   [ ] Add `try-catch` for unexpected exceptions.
    *   [ ] Implement `refreshBearerTokens(oldTokens: BearerTokens?)` method:
        *   [ ] Determine `accountId` (from active holder or `oldTokens`).
        *   [ ] Core logic similar to `getBearerTokens()`: call `getFreshAccessToken`, handle
            results.
        *   [ ] Use mutex if added.
2. **Google Ktor Integration (DI Module - e.g., `BackendGoogleModule.kt`):**
    *   [ ] Locate or create the relevant DI module.
    *   [ ] Define named Ktor `bearer` auth provider (e.g.,
        `GOOGLE_AUTH_PROVIDER_NAME = "googleAuth"`).
    *   [ ] Implement `loadTokens` lambda (call `googleKtorTokenProvider.getBearerTokens()`, handle
        exceptions).
    *   [ ] Implement `refreshTokens` lambda (call
        `googleKtorTokenProvider.refreshBearerTokens(oldTokens)`, handle exceptions).
    *   [ ] Implement `validate` lambda:
        *   [ ] Retrieve `accountId` from `credentials.refreshToken`.
        *   [ ] Call `googleAuthManager.getAccount(accountId)`.
        *   [ ] Construct and return `UserPrincipal` or `null`.
        *   [ ] Handle potential exceptions during validation.
    *   [ ] Ensure all necessary imports.
3. **Review and Refine `GoogleAuthManager.kt`:**
    *   [ ] **`getFreshAccessToken(accountId)`:** Verify robust AppAuth usage (
        `AuthState.performActionWithFreshTokens`), `AuthState` persistence, correct
        `GetFreshAccessTokenResult` emission, error wrapping.
    *   [ ] **`signInInteractive`:** Review purpose, compare with standard AppAuth flow, plan for
        removal if redundant. Ensure AppAuth initiation is UI-driven and `GoogleAuthManager` handles
        `AuthorizationResponse`/`AuthorizationException`.
    *   [ ] **`getAccount(accountId)`:** Verify correct retrieval of `ManagedGoogleAccount` for
        `UserPrincipal` creation.
    *   [ ] **AppAuth `AuthState` Persistence:** Confirm secure serialization and loading.
    *   [ ] **User Info Endpoint Calls:** Review if/how user profile info is fetched and populated.
    *   [ ] **`signOut(accountId)`:** Ensure it clears `AuthState`, `ManagedGoogleAccount`, and
        considers browser session cleanup.

**D. Data Layer (Section 5):**

1. **Review Account Capabilities (e.g., `GoogleAccountCapability.kt`):**
    *   [ ] List all account capability interfaces and implementations.
    *   [ ] For each method: analyze purpose, compare with Ktor client potential, identify
        redundancy vs. unique value.
    *   [ ] Plan deprecation/refactoring for redundant capabilities.

**E. General Tasks:**

1. **Logging:**
    *   [ ] Ensure consistent, informative logging (e.g., Timber) across all new/modified
        authentication components.
2. **Code Cleanup:**
    *   [ ] Remove unused imports in modified files.
    *   [ ] Remove any dead code identified during refactoring (especially old Google auth logic).
3. **Build Verification:**
    *   [ ] Periodically run `./gradlew build` to catch compilation issues early.

**(Later Task - Post Core Refactor) Review `MainViewModel` / UI Layer (Section 6 - original item 8):
**

*   [ ] Investigate and refactor any Google-specific account handling or UI flows in `MainViewModel`
    or related UI components that might be redundant or conflict with the new centralized auth
    logic.

## 7. Assumptions and Soft Spots

* **`ActiveAccountHolder` Correctness:** The entire multi-account Ktor flow relies on
  `ActiveMicrosoftAccountHolder` and `ActiveGoogleAccountHolder` accurately providing the ID of the
  currently selected account for API calls. Any bugs here will lead to tokens being fetched for the
  wrong user.
* **`GoogleAuthManager.getFreshAccessToken` Robustness:** The success of the
  `GoogleKtorTokenProvider` heavily depends on `GoogleAuthManager.getFreshAccessToken` correctly
  handling the AppAuth `AuthState.performActionWithFreshTokens` logic, including internal refresh
  token usage and proper error reporting through `GetFreshAccessTokenResult`.
* **Ktor `validate` Block Logic:** The proposed logic for the `validate` lambda (extracting
  `accountId` from `BearerTokens.refreshToken`, fetching the full `ManagedAccount` via the
  respective `AuthManager`, then creating a `UserPrincipal`) is sound but must be implemented
  carefully to ensure all edge cases (e.g., account deleted between token issuance and validation)
  are handled gracefully.
* **Error Handling and UI Feedback:** While the plan defines `NeedsReauthenticationException` and
  `TokenProviderException`, the Ktor client and ultimately the UI layer must be prepared to catch
  these and provide appropriate user feedback (e.g., prompt for re-login, show error messages).
* **Dependency Injection (DI):** All components (`AuthManager`s, `TokenProvider`s,
  `AccountRepository`, `ActiveAccountHolder`s) must be correctly scoped and injected via DI (e.g.,
  Hilt, Koin) into Ktor modules and other necessary classes.
* **Concurrency:**
    * `MicrosoftKtorTokenProvider` uses a mutex for `refreshBearerTokens`. A similar safeguard might
      be needed for `GoogleKtorTokenProvider` if `googleAuthManager.getFreshAccessToken` isn't
      inherently safe for concurrent calls for the same account.
    * Race conditions around active account switching while token operations are in flight could be
      a concern, though the Ktor auth lifecycle should generally handle this per request.
* **Convention for `BearerTokens.refreshToken`:** Using the `refreshToken` field of Ktor's
  `BearerTokens` to store the `accountId` is a project-specific convention. This works because the
  actual refresh tokens are managed internally by MSAL and `GoogleAuthManager` (via AppAuth
  `AuthState`). This deviation from the field's typical OAuth meaning should be well-understood by
  the team.
* **Cleanup of Old Google Auth Code:** The previous state of Google authentication (
  `GoogleKtorTokenProvider` referencing `GoogleAccountInfo`, etc.) indicates a significant shift.
  Thorough cleanup of any unused classes or logic related to the old approach is essential to avoid
  confusion and potential bugs.
* **Scope of `defaultGraphApiScopes` (Microsoft):** The `MicrosoftKtorTokenProvider` uses
  `listOf("https://graph.microsoft.com/.default")`. This requests all statically configured
  application permissions for Graph API. If more granular or dynamic scopes are needed per API call,
  this might require adjustment, though for a general API client, `.default` is often suitable.

## 8. Definition of Done (for this Refactoring Phase)

This phase of refactoring will be considered complete when:

1. Both `MicrosoftKtorTokenProvider` and `GoogleKtorTokenProvider` correctly implement the
   `TokenProvider` interface.
2. Ktor's `Authentication` plugin is configured with `bearer` providers for both Microsoft (
   `"ms-auth"`) and Google (`"google-auth"`), utilizing their respective `KtorTokenProvider`
   implementations.
3. The `validate` lambda for each provider correctly populates a `UserPrincipal` upon successful
   token validation, using the `accountId` stored in `BearerTokens.refreshToken` to fetch full
   account details.
4. Token refresh mechanisms (`refreshTokens` lambda) are functional for both providers.
5. `NeedsReauthenticationException` is correctly thrown by token providers when user interaction is
   required, and `accountRepository.markAccountForReauthentication` is called.
6. `TokenProviderException` is used for other token acquisition errors.
7. The `GoogleAuthManager.signInInteractive` method is removed or its use cases are clarified and
   handled via standard AppAuth flows.
8. `GoogleAuthManager.getFreshAccessToken` is confirmed to be robust and effectively uses AppAuth
   for token management.
9. The application builds successfully without errors related to the authentication modules.
10. Authenticated Ktor API calls can be successfully made using tokens from both Microsoft and
    Google accounts.
11. Code related to the previous, problematic Google authentication approach (e.g., direct
    `GoogleAccountInfo` usage in Ktor providers) has been removed. 