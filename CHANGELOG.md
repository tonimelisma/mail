# Changelog

**Date:** Current Date

This changelog details significant updates and refactorings made to the codebase and documentation
during the recent session.

## Architectural & Dependency Updates

### 1. `androidx.credentials.CredentialManager` Cleanup

Based on a thorough review, `androidx.credentials.CredentialManager` was found to be unused in the
production Google Sign-In flow, which exclusively uses AppAuth orchestrated by `GoogleAuthManager`.

* **`backend-google/build.gradle.kts`:**
    * Removed `testImplementation(libs.androidx.credentials)`. The dependency was present for
      testing exceptions but no active test code was found utilizing it.
    * Removed `testImplementation(libs.google.id)`. Similar to the above, this dependency (for
      `GoogleIdTokenParsingException`) was found to be unused in active test code.
    * Removed associated comments that referred to these test dependencies.

* **`backend-google/src/main/AndroidManifest.xml`:**
    * Removed the `<uses-permission android:name="android.permission.CREDENTIAL_MANAGER_SERVICE" />`
      permission. As `CredentialManager` is not used in the runtime application for Google Sign-In,
      this permission was deemed unnecessary for the `:backend-google` module.

* **`backend-google/src/test/java/net/melisma/backend_google/errors/GoogleErrorMapperTest.kt`:**
    * Removed obsolete comments at the beginning of the file that referred to importing
      `androidx.credentials` exceptions. These comments were no longer relevant after the removal of
      the test dependencies.

### 2. Build Configuration Cleanup

* **`app/build.gradle.kts`:**
    * Modified an informational comment within the `testOptions.unitTests.all` block. The comment
      `// it.ignoreFailures = true // TODO: Removed as per R-CRITICAL-01` was changed to
      `// it.ignoreFailures = true // Removed as per R-CRITICAL-01` to remove the
      no-longer-actionable `TODO` prefix.

## Documentation Updates

### 1. Creation of `DESIGN2.md`

* A new architectural design document, `DESIGN2.md`, was created from scratch.
* **Purpose:** To provide a completely up-to-date and accurate representation of the project's
  architecture, particularly reflecting the refined authentication flows for both Google (AppAuth
  with `GoogleAuthManager`) and Microsoft (MSAL with `MicrosoftAuthManager` and
  `MicrosoftTokenPersistenceService`).
* This new document incorporates all learnings and refactorings discussed, ensuring future
  development is based on the current state of the codebase.

### 2. Identification of Obsolete Code (Manual Action Recommended)

* **`data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`:**
    * During the review process, several large blocks of commented-out code were identified as
      obsolete (e.g., old `onAuthStateChanged` method, old `addAccount` and `removeAccount` methods,
      and their provider-specific helpers).
    * While automated removal was not consistently successful, these sections are confirmed to be no
      longer in use and can be safely **manually deleted** to improve code readability and
      maintainability.

## Summary of Impact

These changes contribute to a cleaner, more maintainable codebase by removing unused dependencies
and permissions, clarifying build configurations, and providing up-to-date architectural
documentation. The primary impact is a more accurate representation of the Google authentication
flow, now correctly documented as solely using AppAuth managed by `GoogleAuthManager`. 

## Code Refactoring and Bug Fixes (Session: Add Current Date/Time Here)

This section details changes made to Kotlin files to address build errors and align with the ongoing
refactoring efforts based on MTPS.md and DESIGN.md.

### `:backend-microsoft` Module

* **`MicrosoftAuthManager.kt`**:
    * Updated `PersistenceResult.Failure` checks to correctly use type arguments (e.g.,
      `PersistenceResult.Failure<*>` or `PersistenceResult.Failure<PersistenceErrorType>`) for
      exhaustive `when` statements.
    * Refactored `getAccount` to be a `suspend fun` using `withContext(ioDispatcher)` and to
      correctly retrieve `displayName` and `tenantId` from the `MicrosoftTokenPersistenceService` or
      fall back to `IAccount` properties.
    * Ensured `signInInteractive` and `acquireTokenSilent` correctly interact with the
      `MicrosoftTokenPersistenceService` to save/enrich account information, including
      `displayNameFromClaims`, and construct `ManagedMicrosoftAccount` instances upon success.
    * Corrected calls to `activeMicrosoftAccountHolder.setActiveMicrosoftAccountId`.
    * Adjusted `signOut` to correctly call `tokenPersistenceService.clearAccountData` and handle its
      `PersistenceResult`.
    * Modified `getAccounts` and `enrichIAccount` to use
      `tokenPersistenceService.getPersistedAccount` for enriching `IAccount` objects into
      `ManagedMicrosoftAccount` objects, handling potential persistence failures gracefully.

* **`MicrosoftTokenPersistenceService.kt`**:
    * Updated all `PersistenceResult.Failure` usages to explicitly include the generic type
      `<PersistenceErrorType>`.
    * Changed `msalAccount.idToken` to `msalAccount.getIdToken()` method call for fetching the ID
      token from an `IAccount` object, preferring `authResult.idToken` if available.
    * Ensured `saveAccountInfo` correctly handles `authResult` being potentially null.
    * Refined `removeAccountFromManagerInternal` and `clearAccountData` logic.

* **`MicrosoftAccountRepository.kt` (as per previous session, now `backend-microsoft/...`)**:
    * Added import for `net.melisma.backend_microsoft.model.ManagedMicrosoftAccount`.
    * Corrected usage of `OverallApplicationAuthState` enum values to match its definition (e.g.,
      `OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED`).
    * Ensured all references to `iAccount` and `displayName` on `ManagedMicrosoftAccount` instances
      are correct based on its definition.
    * Updated logic in `observeMicrosoftAuthManagerChanges` to correctly create `Account` objects
      for the UI layer, including fetching `displayName` and handling `needsReauthentication` state
      based on silent token acquisition attempts.

* **`MicrosoftKtorTokenProvider.kt`**:
    * Updated to call the `suspend fun microsoftAuthManager.getAccount(activeAccountId)` to retrieve
      `ManagedMicrosoftAccount`.
    * Ensured that `iAccount` and user details (`username`/`displayName`) are accessed correctly
      from the retrieved `ManagedMicrosoftAccount`.
    * Wrapped calls in `externalScope.async(ioDispatcher)` or similar to ensure they are executed
      within a coroutine context when calling suspend functions from a non-suspend interface method.

### `:backend-google` Module

* **`GoogleAuthManager.kt`**:
    * Updated `PersistenceResult.Failure` checks to use `<GooglePersistenceErrorType>`.
    * Modified `signInInteractive` to correctly use `appAuthHelperService.buildAuthorizationRequest`
      and `appAuthHelperService.createAuthorizationRequestIntent`, providing `clientId` (from
      `BuildConfig.GOOGLE_ANDROID_CLIENT_ID`) and `redirectUri` (hardcoded with a TODO for
      configurability, e.g., "net.melisma.android:/oauth2redirect").
    * Refactored `handleAuthorizationResponse` to correctly call
      `appAuthHelperService.exchangeAuthorizationCode` (suspend fun), parse `ParsedIdTokenInfo`,
      save tokens via `tokenPersistenceService.saveTokens` (which now takes `ParsedIdTokenInfo` and
      `TokenResponse`), and create `ManagedGoogleAccount`.
    * Corrected `getAccount` and `getAccounts` to use `userInfo.accountId` (from
      `GoogleTokenPersistenceService.UserInfo`) instead of `userInfo.id` when creating
      `ManagedGoogleAccount` instances.
    * Adjusted various internal logging calls and error handling messages for clarity.

* **`GoogleTokenPersistenceService.kt`**:
    * Injected `AppAuthHelperService` into the constructor.
    * Updated `saveTokens` to use the injected `appAuthHelperService.serviceConfiguration` when
      creating an `AuthState`.
    * The `saveTokens` method signature was updated to accept `ParsedIdTokenInfo` and
      `TokenResponse` directly for saving account details and auth state respectively (this was an
      implicit change driven by `GoogleAuthManager` refactoring).
    * Ensured all `PersistenceResult.Failure` usages correctly specify
      `<GooglePersistenceErrorType>`.
    * The internal `UserInfo` data class was confirmed to have `accountId` (previously `id`),
      aligning with usage in `GoogleAuthManager`.

* **`AppAuthHelperService.kt`**:
    * The `serviceConfiguration` property was made `internal` (changed from `private lateinit var`
      to `internal val ... by lazy`) to allow access from `GoogleTokenPersistenceService` for
      `AuthState` creation.
    * Constructor and `buildAuthorizationRequest` signature were confirmed to be consistent with
      `GoogleAuthManager` usage (constructor takes `@ApplicationContext`,
      `buildAuthorizationRequest` takes `clientId` and `redirectUri`).

</rewritten_file> 