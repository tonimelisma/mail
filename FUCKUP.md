# Build Troubleshooting Log (aka FUCKUP.md)

This document logs the attempts to build the project and fix the encountered errors.

## Attempt 1: Initial Build

* **Command:** `./gradlew build`
* **Result:** Build FAILED.
* **Key Errors:**
    1. `MicrosoftErrorMapper.kt`:
       `'public' function exposes its 'internal' return type 'MappedAuthErrorDetails'`.
    2. `MicrosoftErrorMapper.kt`: `Unresolved reference 'NO_ACCOUNT_FOUND'`.
    3. `MicrosoftAccountRepository.kt`:
       `Class 'MicrosoftAccountRepository' is not abstract and does not implement abstract member 'signIn'`.
    4. `MicrosoftAccountRepository.kt`: `'signIn' overrides nothing` (signature mismatch).
    5. `MicrosoftAccountRepository.kt`: `Too many arguments for 'fun signInInteractive(...)'`.
    6. `MicrosoftAccountRepository.kt`: `'handleAuthenticationResult' overrides nothing` (signature
       mismatch).

## Attempt 2: Fixing `backend-microsoft` Module

### 2.1. Fixes for `MicrosoftErrorMapper.kt`

* **File Investigated:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/errors/MicrosoftErrorMapper.kt`
* **Reasoning & Changes:**
    * The `MappedAuthErrorDetails` data class was `internal` but used as a return type for public
      functions.
        * **Change:** Modified `internal data class MappedAuthErrorDetails` to
          `data class MappedAuthErrorDetails` (making it public by default).
    * The constant `MsalClientException.NO_ACCOUNT_FOUND` was not valid. It appeared to be a typo of
      `MsalClientException.NO_CURRENT_ACCOUNT`.
        * **Change:** Replaced `MsalClientException.NO_ACCOUNT_FOUND` with
          `MsalClientException.NO_CURRENT_ACCOUNT` in the `when` block.
* **File Modified:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/errors/MicrosoftErrorMapper.kt`

### 2.2. Fixes for `MicrosoftAccountRepository.kt`

* **Files Investigated:**
    *
    `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt`
    * `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt` (
      to check `signInInteractive` signature)
* **Reasoning & Changes:**
    * `signIn` method signature did not match the `AccountRepository` interface (missing
      `providerType: String`).
        * **Change:** Added `providerType: String` to the `signIn` method signature. Added a check
          within the method to ensure `providerType` is for Microsoft.
    * The call to `microsoftAuthManager.signInInteractive(...)` included a `loginHint` parameter,
      which is not accepted by the actual method definition in `MicrosoftAuthManager`.
        * **Change:** Removed the `loginHint` argument from the `signInInteractive` call.
    * `handleAuthenticationResult` method signature did not match the `AccountRepository`
      interface (had an extra `activity: Activity` parameter).
        * **Change:** Removed the `activity: Activity` parameter from the
          `handleAuthenticationResult` method signature.
* **File Modified:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt`

* **Build Command after fixes:** `./gradlew build`
* **Result:** Build FAILED.
* **New Key Error:**
    * `:data:kspDebugKotlin FAILED`:
      `InjectProcessingStep was unable to process 'DefaultAccountRepository(...)' because 'ActiveGoogleAccountHolder' could not be resolved.`

## Attempt 3: Fixing KSP Resolution for `ActiveGoogleAccountHolder`

* **Files Investigated:**
    * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt` (to check for
      imports)
    * `backend-google/src/main/java/net/melisma/backend_google/auth/ActiveGoogleAccountHolder.kt` (
      to check definition and annotations)
    * `data/build.gradle.kts` (to check module dependencies)
* **Reasoning & Changes:**
    * `DefaultAccountRepository.kt` was missing an import for `ActiveGoogleAccountHolder`.
        * **Change:** Added `import net.melisma.backend_google.auth.ActiveGoogleAccountHolder` to
          `DefaultAccountRepository.kt`.
    * Checked that `ActiveGoogleAccountHolder` was public and had correct Hilt annotations (
      `@Singleton`, `@Inject constructor`).
    * Checked that the `data` module had a dependency on `:backend-google` in its
      `build.gradle.kts`.
* **File Modified:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`

* **Build Command after fixes:** `./gradlew clean build` (used clean build to avoid stale KSP cache)
* **Result:** Build FAILED.
* **New Key Errors:** The KSP error for `ActiveGoogleAccountHolder` was resolved, but numerous new
  compilation errors appeared in `DefaultAccountRepository.kt`, indicating significant misalignment
  with its `AccountRepository` interface and missing dependencies/imports. Examples:
    *
    `Class 'DefaultAccountRepository' is not abstract and does not implement abstract member 'observeActionMessages'.`
    * `'accounts' overrides nothing.`
    *
    `'overallApplicationAuthState' hides member of supertype 'AccountRepository' and needs an 'override' modifier.`
    * Many `Unresolved reference` errors (e.g., `flatMapLatest`, `AuthorizationRequest`,
      `IOException`, `flow`, `emit`).

## Attempt 4: Extensive Refactoring of `DefaultAccountRepository.kt`

* **Files Investigated:**
    * `core-data/src/main/java/net/melisma/core_data/repository/AccountRepository.kt` (Interface
      definition)
    * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt` (Implementation
      file)
* **Reasoning & Changes:**
    * `DefaultAccountRepository.kt` had multiple functions and properties that did not correctly
      override or implement the `AccountRepository` interface.
    * Many necessary imports for Kotlin Flow operators, Java classes, and project-specific classes
      were missing.
    * Logic for handling Google and Microsoft sign-in, sign-out, and result handling needed
      significant updates to align with the interface and correct method calls.
    * **Specific Changes Made:**
        * **Interface Alignment:**
            * Changed `override val accounts: StateFlow<List<Account>>` to
              `override fun getAccounts(): Flow<List<Account>>`.
            * Changed `override val accountActionMessage: Flow<String?>` to
              `override fun observeActionMessages(): Flow<String?>`.
            * Added `override` modifier to `overallApplicationAuthState`.
            * Implemented `signIn`, `handleAuthenticationResult`, `signOut`, `clearActionMessage`,
              and `markAccountForReauthentication` to match the interface signatures and expected
              behavior. This involved significant refactoring of the existing logic for Google (
              AppAuth) and delegation to `microsoftAccountRepository` for Microsoft.
        * **Imports Added:**
            * `kotlinx.coroutines.flow.flatMapLatest`
            * `java.io.IOException`
            * `kotlinx.coroutines.flow.flow`
            * `kotlinx.coroutines.channels.awaitClose`
            * Ensured `net.openid.appauth.AuthorizationRequest` was correctly used.
        * **Logic Correction:**
            * Updated the `signIn` method to correctly delegate to
              `microsoftAccountRepository.signIn` with all required parameters (`providerType`).
            * Refactored Google sign-in logic within `signIn` to use `callbackFlow`,
              `appAuthHelperService.getAuthorizationRequestIntent`, and `googleAuthResultChannel`.
            * Refactored `handleAuthenticationResult` for Google to correctly use
              `AuthorizationResponse.fromIntent`, `AuthorizationException.fromIntent`, and delegate
              to `appAuthHelperService.handleAuthorizationResponse`, then process the result for
              token persistence and user info fetching via `googleTokenPersistenceService`.
            * Refactored `signOut` for Google to use `googleTokenPersistenceService` for token
              revocation and clearing.
            * Implemented `updateCombinedAccountsAndOverallAuthState` to correctly merge accounts
              from both Microsoft and Google sources and determine the overall application
              authentication state.
* **File Modified:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`

* **Build Command after fixes:** `./gradlew build`
* **Result:** Build FAILED.
* **New Key Errors in `DefaultAccountRepository.kt`:**
    * `Conflicting overloads: fun getAccounts(): Flow<List<Account>>` (at two locations).
    * Multiple `Unresolved reference` errors related to methods in `appAuthHelperService` (e.g.,
      `getAuthorizationRequestIntent`, `handleAuthorizationResponse`) and
      `googleTokenPersistenceService` (e.g., `persistAuthState`, `updateUserInfoFromNetwork`,
      `revokeToken`), and properties on their return types (e.g., `isSuccess`, `exceptionOrNull`,
      `id`, `email`, `displayName`).
    * Unresolved reference `USER_INFO_FAILED` (an enum value in `GenericAuthErrorType`).
    * Argument type mismatches.
* **Additional Errors:** Test failures in `:backend-google:testDebugUnitTest`.

**(Further investigation into `AppAuthHelperService.kt` and `GoogleTokenPersistenceService.kt`
definitions and `GenericAuthErrorType` was initiated before this documentation step.)** 