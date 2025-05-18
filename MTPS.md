# Microsoft Token Persistence Service (MTPS) Refactoring Debrief

**Date:** May 13, 2025

## 1. Objectives

The primary objective of this refactoring was to improve the separation of concerns within the
`:backend-microsoft` module by centralizing all interactions with Android's `AccountManager` for
Microsoft accounts into a new, dedicated service: `MicrosoftTokenPersistenceService.kt`.

This aimed to:

1. **Mimic Google Backend Structure:** Align the Microsoft authentication backend more closely with
   the Google backend, which already utilized a `GoogleTokenPersistenceService` for similar
   purposes.
2. **Simplify `MicrosoftAuthManager`:** Reduce the responsibilities of `MicrosoftAuthManager.kt`,
   making it focus solely on MSAL (Microsoft Authentication Library) interactions (e.g., sign-in/out
   flows, token acquisition/refresh) and not direct `AccountManager` data manipulation.
3. **Improve Code Clarity & Maintainability:** Make the persistence logic for Microsoft accounts
   more explicit, easier to find, and maintain by having it in one place.
4. **Enhance Testability:** Allow for more focused unit testing of persistence logic separate from
   MSAL interaction logic.

## 2. Summary of Changes

The following key changes were made across several files:

1. **`PersistedMicrosoftAccount.kt` Created (`:backend-microsoft`):
    * **File:**
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/model/PersistedMicrosoftAccount.kt`
    * **Change:** A new public data class `PersistedMicrosoftAccount` was created to represent the
      structure of Microsoft account data stored in `AccountManager`. This class was moved from
      being a private inner class within `MicrosoftAuthManager.kt`.

2. **`MicrosoftTokenPersistenceService.kt` Created (`:backend-microsoft`):
    * **File:**
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftTokenPersistenceService.kt`
    * **Change:** This new service was created to handle all CRUD (Create, Read, Update, Delete)
      operations for Microsoft account data using `AccountManager`.
    * It defines constants for `AccountManager` keys (e.g., `ACCOUNT_TYPE_MICROSOFT`,
      `KEY_ACCESS_TOKEN`, `KEY_ID_TOKEN`, `KEY_DISPLAY_NAME`).
    * It uses `SecureEncryptionService` to encrypt/decrypt sensitive tokens (Access and ID tokens).
    * **Key methods implemented:**
        *
        `saveAccountInfo(msalAccount: IAccount, authResult: IAuthenticationResult?, displayNameFromClaims: String?): Boolean`
        * `getPersistedAccount(accountManagerName: String): PersistedMicrosoftAccount?`
        * `getAllPersistedAccounts(): List<PersistedMicrosoftAccount>`
        * `getPersistedAccessToken(accountManagerName: String): String?`
        * `getPersistedIdToken(accountManagerName: String): String?`
        * `clearAccountData(accountManagerName: String, removeAccountFromManager: Boolean): Boolean`

3. **DI Module for Persistence Service Created (`:backend-microsoft`):
    * **File:**
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/MicrosoftPersistenceModule.kt`
    * **Change:** A new Hilt module was created to provide `MicrosoftTokenPersistenceService` as a
      singleton.

4. **`MicrosoftAuthManager.kt` Refactored (`:backend-microsoft`):
    * **Change:**
        * Removed all direct `AccountManager` interactions and private persistence-related methods (
          e.g., `saveAccountInfoToAccountManager`, `getPersistedAccountData`).
        * Removed constants for `AccountManager` keys and the private `PersistedMicrosoftAccount`
          data class definition.
        * Injected `MicrosoftTokenPersistenceService` and `ActiveMicrosoftAccountHolder`.
        * `signInInteractive` now calls `tokenPersistenceService.saveAccountInfo(...)` on success
          and then updates `activeMicrosoftAccountHolder`.
        * `signOut` now calls `tokenPersistenceService.clearAccountData(...)` after MSAL's
          `removeAccount` succeeds, and then updates `activeMicrosoftAccountHolder`.
        * The manager now focuses purely on MSAL API interactions for auth flows and token
          management, delegating storage to the new persistence service.

5. **`MicrosoftAccountRepository.kt` Refactored (`:backend-microsoft`):
    * **Change:**
        * Injected `MicrosoftTokenPersistenceService`.
        * In `observeMicrosoftAuthManagerChanges()` and `signIn()`, when constructing the `Account`
          domain model, it now calls `tokenPersistenceService.getPersistedAccount(...)` to fetch the
          persisted display name, providing a richer user experience. This makes the
          `Account.username` field potentially hold a display name rather than just the UPN/email if
          available.
        * Minor adjustments to `signOut` for robust local state clearing and calling
          `tokenPersistenceService.clearAccountData` as a fallback if MSAL couldn't find the
          account.

## 3. The Plan vs. Execution

The refactoring generally followed the detailed plan:

1. **Phase 1 (Understand Existing Persistence):** Achieved by reading
   `GoogleTokenPersistenceService.kt` and analyzing persistence logic within
   `MicrosoftAuthManager.kt`.
2. **Phase 2 (Design and Create `MicrosoftTokenPersistenceService.kt`):
    * Defining `PersistedMicrosoftAccount.kt` (public): Executed as planned.
    * Creating `MicrosoftTokenPersistenceService.kt` with its methods and constants: Executed as
      planned. The methods mirrored the identified needs from `MicrosoftAuthManager` and drew
      inspiration from `GoogleTokenPersistenceService`.
3. **Phase 3 (Refactor Existing Components):
    * DI Update for `MicrosoftTokenPersistenceService`: A new Hilt module
      `MicrosoftPersistenceModule.kt` was created as planned.
    * Refactoring `MicrosoftAuthManager.kt`: Executed as planned – injected the new service, removed
      old persistence code, and updated `signInInteractive` and `signOut`.
    * Refactoring `MicrosoftAccountRepository.kt`: Executed with a slight design choice during
      implementation. The plan considered having `MicrosoftAuthManager` provide richer account
      objects, but to minimize changes to `MicrosoftAuthManager`'s API at this stage,
      `MicrosoftAccountRepository` was made to inject and use `MicrosoftTokenPersistenceService`
      directly for fetching supplementary data (like `displayName`).
4. **Phase 4 (Verification):** This phase is pending manual testing by the user.

## 4. Deviations, Surprises, and Discoveries

* **Minor Deviation in `MicrosoftAccountRepository`:** As noted above, instead of
  `MicrosoftAuthManager` fetching and combining persisted data with `IAccount` objects,
  `MicrosoftAccountRepository` now directly uses `MicrosoftTokenPersistenceService` for enriching
  the `Account` domain model with the persisted display name. This was a pragmatic choice to limit
  the scope of changes to `MicrosoftAuthManager` but introduces a direct dependency from the
  repository to the specific persistence service.
* **`AccountManager.removeAccount` Deprecation:** The `removeAccountFromManagerInternal` method in
  `MicrosoftTokenPersistenceService.kt` uses the deprecated `AccountManager.removeAccount(...)`
  call. While this was done for consistency with a potential pattern (and to ensure asynchronous
  removal if needed), `AccountManager.removeAccountExplicitly()` is the more modern non-deprecated
  synchronous counterpart. This was a conscious choice for the initial port but could be revisited.
  `GoogleTokenPersistenceService` uses `removeAccountExplicitly`. The key difference is that
  `removeAccountExplicitly` is synchronous and returns a boolean, while the deprecated
  `removeAccount` returns a `Future<Bundle>`.
* **Scope of Persisted Data:** `MicrosoftTokenPersistenceService` now also persists `scopes` and
  `expiresOnTimestamp` obtained from `IAuthenticationResult`, similar to how `MicrosoftAuthManager`
  used to. This wasn't explicitly a primary goal but was carried over from the existing logic. The
  utility of re-persisting these (especially `expiresOnTimestamp` which MSAL tracks) could be
  debated, but it maintains parity.
* **`ActiveMicrosoftAccountHolder` Integration:** The refactoring ensured
  `ActiveMicrosoftAccountHolder` is correctly updated by `MicrosoftAuthManager` after successful
  persistence operations handled by `MicrosoftTokenPersistenceService`.

## 5. Soft Spots & Potential Areas for Improvement

* **`MicrosoftAccountRepository`'s Direct Dependency:** The direct injection of
  `MicrosoftTokenPersistenceService` into `MicrosoftAccountRepository` is a minor soft spot.
  Ideally, repositories might interact with a manager/coordinator class that abstracts away the
  specific persistence service if a cleaner separation is desired in the future. For now, it's
  functional and isolated to the `:backend-microsoft` module.
* **Error Handling in Persistence:** While basic try-catch blocks are in place, error handling
  within `MicrosoftTokenPersistenceService` (e.g., if `SecureEncryptionService` fails or
  `AccountManager` calls fail) could be made more granular, potentially returning more specific
  error types rather than just `false` or `null`. This would allow callers to react more
  intelligently.
* **Use of Deprecated `AccountManager.removeAccount`:** As mentioned, this could be updated to use
  non-deprecated APIs if the synchronous nature of `removeAccountExplicitly` is acceptable for all
  use cases (especially the pre-emptive removal during `saveAccountInfo`). The current
  implementation in `MicrosoftTokenPersistenceService` uses the deprecated version, which returns a
  `Future` and was a quick port. `GoogleTokenPersistenceService` uses `removeAccountExplicitly()`
  which is synchronous.
* **Token Caching:** `MicrosoftAuthManager` has a commented-out section for potentially updating
  persisted tokens during `acquireTokenSilent`. The current refactoring doesn't implement this token
  update in the persistence service. MSAL is generally the source of truth for fresh tokens. If the
  app *did* want to cache the latest access/ID tokens from silent refreshes in `AccountManager` (
  e.g., for quick, non-critical reads), this would need to be added to
  `MicrosoftTokenPersistenceService` and called appropriately from `MicrosoftAuthManager`.
* **Consistency of `accountManagerName`:** The `accountManagerName` is `IAccount.getId()`. This is
  used consistently. `PersistedMicrosoftAccount` also has `msalAccountId` which is the same. This is
  fine but slightly redundant in the data class itself if `accountManagerName` is always the source
  ID.

## 6. Workarounds & Short-Term Solutions

* The direct use of `MicrosoftTokenPersistenceService` in `MicrosoftAccountRepository` can be seen
  as a short-term solution to avoid immediate changes to `MicrosoftAuthManager`'s public API for
  fetching enriched account objects.
* Using the deprecated `AccountManager.removeAccount` was a quick way to implement the removal
  logic, matching a potential pattern for future asynchronous handling, but should be reviewed for
  modern best practices.

## 7. Architectural Smells

* **Minor Information Envy (Potentially):** `MicrosoftAccountRepository` reaching into
  `MicrosoftTokenPersistenceService` for display names could be seen as a slight smell. If this
  pattern grows, abstracting this behind `MicrosoftAuthManager` would be cleaner.
* **Primitive Obsession with Keys:** The use of multiple `String` constants for `AccountManager`
  keys is standard but always carries a risk of typos if not managed carefully. A more typed or
  structured approach to `AccountManager` data bundles could be considered in a larger overhaul, but
  that's beyond this refactoring's scope.

## 8. Conclusion

This refactoring successfully met its primary goals of creating `MicrosoftTokenPersistenceService`
and simplifying `MicrosoftAuthManager`. The Microsoft backend's persistence logic is now more
centralized and aligned with the Google backend's pattern. The identified soft spots and potential
improvements can be addressed in future iterations if deemed necessary.

---

## 9. Refactoring Iteration 2: Enhanced Error Handling & Architectural Alignment (by Senior Dev)

**Date:** May 14, 2025 *(Adjusted Date)*

### 9.1. Objectives of This Iteration

The primary objectives of this second iteration of refactoring were to:

1. **Enhance Robustness of Persistence Layer:** Introduce detailed and structured error reporting
   for operations within `MicrosoftTokenPersistenceService`, moving away from simple boolean or
   nullable return types. This allows for better diagnostics and more informed error handling by
   callers.
2. **Improve Architectural Abstraction:** Ensure that `MicrosoftAuthManager` serves as the sole
   intermediary between `MicrosoftAccountRepository` (and other potential consumers) and the
   `MicrosoftTokenPersistenceService`. This encapsulates persistence details more cleanly and aligns
   better with the principle of a manager class handling all aspects of its domain.
3. **Address Minor Issues from Initial Refactoring:** Correct the use of deprecated APIs identified
   in the first iteration and remove unused or empty files to maintain codebase hygiene.

### 9.2. Summary of Key Changes in This Iteration

The following key changes were implemented:

1. **Advanced Error Handling for Persistence (`:backend-microsoft`):**
    * **`PersistenceResult.kt` & `PersistenceErrorType.kt` Created:**
        * **Files:**
            *
            `backend-microsoft/src/main/java/net/melisma/backend_microsoft/common/PersistenceErrorType.kt`
            *
            `backend-microsoft/src/main/java/net/melisma/backend_microsoft/common/PersistenceResult.kt`
        * **Change:** A new sealed class `PersistenceResult<out T>` (with `Success<T>` and `Failure`
          states) and an enum `PersistenceErrorType` were introduced. These provide a structured way
          to communicate the outcome of persistence operations, including specific error types and
          optional messages/causes.
    * **`MicrosoftTokenPersistenceService.kt` Refactored:**
        * **Change:** All public methods (e.g., `saveAccountInfo`, `getPersistedAccount`,
          `getPersistedAccessToken`, `clearAccountData`) were modified to return the new
          `PersistenceResult<T>` type instead of `Boolean` or `T?`. This change is fundamental to
          providing richer error information.
    * **Callers Updated (`MicrosoftAuthManager.kt`, `MicrosoftAccountRepository.kt`):**
        * **Change:** These classes were updated to correctly handle the new `PersistenceResult`
          returned by `MicrosoftTokenPersistenceService`. Error handling now involves checking for
          `PersistenceResult.Failure` and logging the detailed `errorType` and `message`, allowing
          for better debugging and awareness of persistence issues.

2. **Improved Abstraction: `Repository -> AuthManager -> PersistenceService` (`:backend-microsoft`):
   **
    * **`ManagedMicrosoftAccount.kt` Created:**
        * **File:**
          `backend-microsoft/src/main/java/net/melisma/backend_microsoft/model/ManagedMicrosoftAccount.kt`
        * **Change:** A new data class was created to encapsulate MSAL's `IAccount` object along
          with supplementary data (like `displayName` and `tenantId`) that is typically fetched from
          the persistence layer.
    * **`MicrosoftAuthManager.kt` Significantly Refactored:**
        * **Change:**
            * `MicrosoftAuthManager` now takes on the responsibility of calling
              `MicrosoftTokenPersistenceService.getPersistedAccount` to enrich `IAccount` objects.
              The result is a `ManagedMicrosoftAccount` object.
            * The public API of `MicrosoftAuthManager` (methods like `getAccounts()`,
              `getAccount()`, and the success states within `AuthenticationResultWrapper`) now
              provide these `ManagedMicrosoftAccount` objects to its clients, abstracting away the
              underlying persistence lookup.
    * **`MicrosoftAccountRepository.kt` Refactored:**
        * **Change:**
            * The direct dependency injection of `MicrosoftTokenPersistenceService` was removed from
              `MicrosoftAccountRepository`.
            * The repository no longer makes direct calls to `tokenPersistenceService`. Instead, it
              consumes the richer `ManagedMicrosoftAccount` objects provided by
              `MicrosoftAuthManager` to obtain details like `displayName`.
            * The specific fallback call to `tokenPersistenceService.clearAccountData` within
              `MicrosoftAccountRepository.signOut()` (which occurred if an MSAL account wasn't found
              locally) was removed. This centralizes the responsibility of persistence clearing
              during sign-out to `MicrosoftAuthManager`, which should handle the complete removal
              process including underlying persisted data.

3. **Corrections to Initial Refactoring (`:backend-microsoft`):**
    * **Deprecated API Usage in `MicrosoftTokenPersistenceService.kt`:**
        * **Change:** The use of the deprecated `AccountManager.removeAccount(...)` was corrected to
          `AccountManager.removeAccountExplicitly(...)`, aligning with modern Android best practices
          and the approach used in `GoogleTokenPersistenceService`.
    * **Empty Hilt Module Deleted:**
        * **File:**
          `backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/MicrosoftPersistenceModule.kt` (
          previous path)
        * **Change:** This file, which was found to be empty, was deleted.
          `MicrosoftTokenPersistenceService` is appropriately annotated for Hilt constructor
          injection, making a dedicated module for it unnecessary unless more complex binding logic
          is required.

### 9.3. Surprises and Discoveries During This Iteration

* **Empty DI Module:** The primary minor surprise was the `MicrosoftPersistenceModule.kt` file being
  empty, which contradicted the initial debrief document. This was easily resolved by deleting the
  unneeded file.
* **Scope of `MicrosoftAuthManager` Changes:** Centralizing the account enrichment logic (combining
  `IAccount` with persisted data) within `MicrosoftAuthManager` required more substantial changes to
  its internal logic and public-facing data types (e.g., `AuthenticationResultWrapper`) than a
  superficial patch. This ensures a cleaner abstraction boundary.

### 9.4. Addressed Code Smells (from previous state)

* **Primitive Error Handling in Persistence:** The previous reliance on simple `Boolean` return
  values or nullability to indicate success/failure in `MicrosoftTokenPersistenceService` was a form
  of "Primitive Obsession" for error states. This has been addressed with the introduction of the
  `PersistenceResult` sealed class, providing structured error information.
* **Information Envy / Faulty Abstraction:** `MicrosoftAccountRepository` previously needed to
  inject and directly query `MicrosoftTokenPersistenceService` for supplementary account details (
  like display names). This was a sign of "Information Envy" and a less-than-ideal abstraction. This
  has been resolved by making `MicrosoftAuthManager` responsible for providing a fully enriched
  `ManagedMicrosoftAccount`.

### 9.5. Remaining Soft Spots & Potential Areas for Improvement

* **Error Propagation to UI/User:** While persistence errors are now more detailed for logging
  within the backend modules, `MicrosoftAuthManager` (and subsequently `MicrosoftAccountRepository`)
  currently tends to log these underlying persistence failures but often allows the primary MSAL
  operation (e.g., sign-in) to proceed as "successful" if MSAL itself reported success. A more
  nuanced strategy might be needed if these underlying persistence issues (e.g., failure to save an
  account after successful auth) should affect the UI or be directly communicated to the user with
  specific error messages. This remains a "soft spot" in how errors are fully handled end-to-end.
* **Scope of Persisted Data (Reiteration from Sec 5):** The points from the original debrief (
  Section 5) regarding the utility of `MicrosoftTokenPersistenceService` storing `scopes` and
  `expiresOnTimestamp` are still valid. A review should be conducted to determine if this data is
  actively used from persistence or if MSAL's own tracking is sufficient, potentially simplifying
  the persisted data model.
* **Commented Code in `MicrosoftTokenPersistenceService`:** The service still has a commented-out
  injection for `ActiveMicrosoftAccountHolder`. Since `MicrosoftAuthManager` is now responsible for
  updating this holder, this commented-out line in `MicrosoftTokenPersistenceService.kt` should be
  removed to avoid confusion and keep the code clean. I will make this small change.
* **SignOut Fallback in `MicrosoftAccountRepository`:** When an account is being signed out, if
  `MicrosoftAccountRepository` doesn't find a corresponding `ManagedMicrosoftAccount` from
  `MicrosoftAuthManager`, it currently clears its local state and emits an error. This is a
  reasonable fallback. However, the underlying persistence for such an "orphaned" account ID (if it
  ever existed and `MicrosoftAuthManager.signOut` wasn't triggered for it) would not be cleared by
  this specific repository path. This scenario implies a potential state inconsistency that might
  need broader consideration if it occurs frequently.

### 9.6. Important Considerations for Teammates

* **Detailed Persistence Errors:** When debugging issues related to Microsoft account persistence,
  the logs will now contain more specific `PersistenceErrorType` information from
  `MicrosoftTokenPersistenceService`, aiding in quicker diagnosis.
* **`MicrosoftAuthManager` as Source of Truth for `ManagedMicrosoftAccount`:** For consumers within
  the `:backend-microsoft` module (and `DefaultAccountRepository` which uses
  `MicrosoftAccountRepository`), `MicrosoftAuthManager` is now the definitive source for
  `ManagedMicrosoftAccount` objects. These objects bundle MSAL's `IAccount` with commonly needed
  persisted data like `displayName`.
* **Centralized Sign-Out Persistence:** The responsibility for ensuring that persisted account data
  is cleared during a sign-out operation is now fully managed within `MicrosoftAuthManager`'s
  `signOut` flow (which in turn calls the updated
  `MicrosoftTokenPersistenceService.clearAccountData`).

--- 

## 10. Proposed Refactoring Plan: Google Backend Alignment (Iteration 1)

**Objective:** To align the `:backend-google` module's architecture with the improvements made in
the `:backend-microsoft` module (as detailed in sections 1-9 of this document), focusing on
structured error handling, clear abstraction layers, and consistent management of the active account
state.

**Overarching Goals:**

1. **Structured Error Handling:** Implement `PersistenceResult` (from `core-data`) throughout the
   Google persistence and authentication flow.
2. **Architectural Alignment:** Create a `GoogleAuthManager` in `:backend-google` to encapsulate
   Google-specific authentication logic, mirroring `MicrosoftAuthManager`.
3. **Clear Separation of Concerns:** Refactor `DefaultAccountRepository` (in `:data`) to delegate
   Google operations to `GoogleAuthManager`, making it a true provider-agnostic repository for auth
   flow execution.
4. **Maintainability & Testability:** Improve these aspects by centralizing provider-specific logic
   within `:backend-google`.

**Detailed Plan:**

### Phase 1: Core Infrastructure (Partially Complete - Verification & Alignment)

* **Step 1.1: Shared `PersistenceResult.kt` in `:core-data`**
    * **Status:** Done. `core-data/src/main/java/net/melisma/core_data/common/PersistenceResult.kt`
      created with the structure:
      ```kotlin
      sealed class PersistenceResult<out T> {
          data class Success<T>(val data: T) : PersistenceResult<T>()
          data class Failure<E : Enum<E>>(val errorType: E, val message: String? = null, val cause: Throwable? = null) : PersistenceResult<Nothing>()
      }
      ```
* **Step 1.2: Update `:backend-microsoft` to Use Shared `PersistenceResult`**
    * **Status:** Done. Local `PersistenceResult.kt` deleted. Imports updated in relevant Microsoft
      backend files.
* **Step 1.3: `GooglePersistenceErrorType.kt` in `:backend-google`**
    * **Status:** Done.
      `backend-google/src/main/java/net/melisma/backend_google/common/GooglePersistenceErrorType.kt`
      created.
* **Step 1.4: Refactor `GoogleTokenPersistenceService.kt`**
    * **Status:** Done.
        * Methods now return `PersistenceResult<ExpectedType>` (using `GooglePersistenceErrorType`
          for failures).
        * `ActiveGoogleAccountHolder` direct updates removed.
        * Internal helper `removeAccountFromManagerInternal` implemented and used.

### Phase 2: `GoogleAuthManager.kt` - Creation and Full Implementation

* **Step 2.1: Define `ManagedGoogleAccount.kt`**
    * **Status:** Done.
      `backend-google/src/main/java/net/melisma/backend_google/model/ManagedGoogleAccount.kt`
      created.
* **Step 2.2: Define `GoogleAuthManager.kt` Skeleton**
    * **Status:** Done. File created with sealed result classes (`GoogleSignInResult`,
      `GoogleSignOutResult`, `GoogleGetTokenResult`), injected dependencies, and `TODO()`
      placeholders.
* **Step 2.3: Implement Helper Functions in `GoogleAuthManager.kt` (Private)**
    * **Action:** Add private functions to map
      `PersistenceResult.Failure<GooglePersistenceErrorType>` to `GoogleSignInResult.Error`,
      `GoogleSignOutResult.Error`, and `GoogleGetTokenResult.Error` respectively.
* **Step 2.4:
  Implement `GoogleAuthManager.signInInteractive(activity: Activity, loginHint: String?): Intent` (
  Suspend Function)**
    * **Details:** This method will now be a `suspend fun` directly returning an `Intent` for the
      AppAuth authorization flow or throwing an exception if intent creation fails. It will use
      `appAuthHelperService` to build the request and intent using appropriate client ID, redirect
      URI, and scopes (e.g.,
      `AppAuthHelperService.MANDATORY_SCOPES + AppAuthHelperService.GMAIL_SCOPES`).
* **Step 2.5:Implement
  `GoogleAuthManager.handleAuthorizationResponse(authResponse: AuthorizationResponse?, authException: AuthorizationException?): Flow<GoogleSignInResult>`
  **
    * **Details:** This flow will process the AppAuth activity result. It will:
        1. Handle `authException` (map to `Cancelled` or `Error`).
        2. Handle null `authResponse` (map to `Error`).
        3. Call `appAuthHelperService.exchangeAuthorizationCode(authResponse)`.
        4. Call `appAuthHelperService.parseIdToken(...)`.
        5. Call `tokenPersistenceService.saveTokens(...)` with the new token data and parsed user
           info.
        6. If persistence succeeds, create `ManagedGoogleAccount`, update
           `activeGoogleAccountHolder`, create `AuthState` from `TokenResponse`, and emit
           `GoogleSignInResult.Success(managedAccount, authState)`.
        7. Handle all exceptions and persistence failures by emitting appropriate
           `GoogleSignInResult.Error` (with persistence failure details if applicable).
* **Step 2.6:
  Implement `GoogleAuthManager.getAccount(accountId: String): Flow<ManagedGoogleAccount?>`**
    * **Details:** Calls `tokenPersistenceService.getUserInfo(accountId)`. Maps
      `PersistenceResult.Success<UserInfo>` to `ManagedGoogleAccount`. Emits `null` if not found or
      on other persistence errors.
* **Step 2.7: Implement `GoogleAuthManager.getAccounts(): Flow<List<ManagedGoogleAccount>>`**
    * **Details:** Calls `tokenPersistenceService.getAllGoogleUserInfos()`. Maps
      `PersistenceResult.Success<List<UserInfo>>` to `List<ManagedGoogleAccount>`. Emits an empty
      list on persistence failure.
* **Step 2.8:Implement
  `GoogleAuthManager.signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult>`**
    * **Details:**
        1. Attempt to get `AuthState` via `tokenPersistenceService.getAuthState` to retrieve
           `refreshToken`.
        2. If `refreshToken` exists, call `appAuthHelperService.revokeToken(refreshToken)`.
        3. Call
           `tokenPersistenceService.clearTokens(managedAccount.accountId, removeAccountFromManagerFlag = true)`.
        4. If clearing is successful, update `activeGoogleAccountHolder` if the signed-out account
           was active.
        5. Emit `GoogleSignOutResult.Success` or `GoogleSignOutResult.Error` based on the outcome of
           persistence and revocation (though revocation failure might be logged as a warning while
           still proceeding with local clear).
* **Step 2.9:
  Implement `GoogleAuthManager.getFreshAccessToken(accountId: String): GoogleGetTokenResult` (
  Suspend Function)**
    * **Details:** This is critical for Ktor.
        1. Retrieve `AuthState` using `tokenPersistenceService.getAuthState(accountId)`.
        2. If `AuthState` needs refresh (or access token is null), call
           `appAuthHelperService.refreshAccessToken(currentAuthState)`.
        3. If refresh is successful, update the `AuthState` using
           `tokenPersistenceService.updateAuthState(accountId, newAuthState)`.
        4. Return `GoogleGetTokenResult.Success(accessToken)` from the (potentially updated)
           `AuthState`.
        5. Handle all errors: `PersistenceResult.Failure` from `getAuthState` or `updateAuthState`
           should map to `GoogleGetTokenResult.Error`. An `AuthorizationException` during refresh (
           especially `invalid_grant`) should map to `GoogleGetTokenResult.NeedsReauthentication` (
           after clearing tokens locally via
           `tokenPersistenceService.clearTokens(accountId, removeAccount = false)`).

### Phase 3: Refactor Consumers

* **Step 3.1: Refactor `DefaultAccountRepository.kt` (in `:data` module)**
    1. **Dependency Changes:** Remove direct injections of `AppAuthHelperService` and
       `GoogleTokenPersistenceService`. Inject `GoogleAuthManager`.
    2. **`signIn` (for "GOOGLE"):** Modify to:
        * Call `googleAuthManager.signInInteractive(activity, loginHint)` (suspend function
          returning `Intent`).
        * Send `GenericAuthResult.UiActionRequired(intent)` to the existing
          `googleAuthResultChannel`.
        * Continue to return `googleAuthResultChannel.receiveAsFlow()`.
    3. **`handleAuthenticationResult` (for "GOOGLE"):**
        * Call `googleAuthManager.handleAuthorizationResponse(authResponse, authException)` (which
          returns `Flow<GoogleSignInResult>`).
        * Collect the `GoogleSignInResult`.
        * Map the `GoogleSignInResult` (Success, Error, Cancelled) to the appropriate
          `GenericAuthResult`.
        * `trySend` the mapped `GenericAuthResult` to `googleAuthResultChannel`.
        * On `GoogleSignInResult.Success`, update the local `_googleAccounts` list (after mapping
          `ManagedGoogleAccount` to `Account`).
    4. **`signOut` (for "GOOGLE"):**
        * Retrieve the `ManagedGoogleAccount` for the given `Account` (e.g., by calling
          `googleAuthManager.getAccount(account.id).firstOrNull()` or maintaining a local map).
        * Call `googleAuthManager.signOut(retrievedManagedGoogleAccount)`.
        * Collect `GoogleSignOutResult`, map to `GenericSignOutResult`, and emit.
        * Update local `_googleAccounts` list on success.
    5. **`fetchPersistedGoogleAccounts` (or similar for initializing `_googleAccounts`):**
        * Change to use `googleAuthManager.getAccounts()` and map the `List<ManagedGoogleAccount>`
          to `List<Account>` for `_googleAccounts.value`.
    6. **`getActiveAccount(PROVIDER_TYPE_GOOGLE)`:**
        * Use `activeGoogleAccountHolder.activeAccountId` to get the ID.
        * Call `googleAuthManager.getAccount(activeId)` and map the `ManagedGoogleAccount?` to
          `Account?`.
    7. **`markAccountForReauthentication(GOOGLE)`:**
        * Ideally, call a new method on `GoogleAuthManager` like
          `requestReauthentication(accountId)`. If not implemented, retain local modification of the
          `Account` object in `_googleAccounts` as a temporary measure.
* **Step 3.2: Refactor `GoogleKtorTokenProvider.kt` (in `:backend-google`)**
    1. **Dependency Changes:** Remove `GoogleTokenPersistenceService` and `AppAuthHelperService`.
       Inject `GoogleAuthManager`.
    2. **`getBearerTokens()`:**
        * Get `accountId` from `activeAccountHolder`.
        * Call `result = googleAuthManager.getFreshAccessToken(accountId)` (suspend function).
        * Handle `GoogleGetTokenResult`:
            * `Success`: Return `BearerTokens(result.accessToken, "")`.
            * `Error`: Log and return `null`.
            * `NeedsReauthentication`: Log and throw
              `GoogleNeedsReauthenticationException(accountId)`.

### Phase 4: Cleanup

* **Step 4.1: Remove Unused Code:** Delete `AppAuthHelperService.GoogleTokenData` if confirmed
  unused.
* **Step 4.2: Final Review of `DefaultAccountRepository.kt`:** Ensure all old Google-specific direct
  calls are removed and it cleanly delegates to `GoogleAuthManager`.
* **Step 4.3: Logging Review:** Ensure consistent and meaningful logging across all refactored
  Google components.
* **Step 4.4: Coroutine Contexts:** Verify correct `Dispatcher` usage for all operations in
  `GoogleAuthManager`.
* **Step 4.5: Testing:** Plan for new unit tests for `GoogleAuthManager` and updates to tests for
  `DefaultAccountRepository` and `GoogleKtorTokenProvider`.

This plan will be used to guide the refactoring work for the Google authentication backend.
Iterative adjustments may be made as implementation proceeds.

---

## 11. Debrief: Google Backend Alignment (Iteration 1) - Implementation Details

**Date:** May 15, 2025 (Actual Implementation Date)

This section details the execution of the "10. Proposed Refactoring Plan: Google Backend Alignment (
Iteration 1)".

### 11.1. Summary of Implemented Changes

The refactoring plan was largely implemented as specified, focusing on introducing
`GoogleAuthManager` and refactoring its consumers.

1. **Phase 2: `GoogleAuthManager.kt` - Creation and Full Implementation (Steps 2.3-2.9)**
    * Successfully created and implemented
      `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleAuthManager.kt`.
    * **Helper Functions (Private):** Implemented for mapping
      `PersistenceResult.Failure<GooglePersistenceErrorType>` to `GoogleSignInResult.Error`,
      `GoogleSignOutResult.Error`, and `GoogleGetTokenResult.Error`.
    * **`signInInteractive(activity: Activity, loginHint: String?): Intent`:** Implemented as a
      `suspend fun` that returns an `Intent` for the AppAuth flow, or throws an
      `IllegalStateException` if intent creation fails. It uses `appAuthHelperService` internally.
    * **
      `handleAuthorizationResponse(authResponse: AuthorizationResponse?, authException: AuthorizationException?): Flow<GoogleSignInResult>`:
      ** Implemented to process the AppAuth activity result. This flow handles exceptions, exchanges
      the authorization code, parses the ID token (via `appAuthHelperService`), saves tokens and
      user info (via `tokenPersistenceService`), updates `activeGoogleAccountHolder`, and emits
      `GoogleSignInResult` states (Success, Error, Cancelled).
    * **`getAccount(accountId: String): Flow<ManagedGoogleAccount?>`:** Implemented to fetch a
      specific `ManagedGoogleAccount` using `tokenPersistenceService.getUserInfo()`.
    * **`getAccounts(): Flow<List<ManagedGoogleAccount>>`:** Implemented to fetch all persisted
      Google accounts as `ManagedGoogleAccount` objects using
      `tokenPersistenceService.getAllGoogleUserInfos()`.
    * **`signOut(managedAccount: ManagedGoogleAccount): Flow<GoogleSignOutResult>`:** Implemented to
      handle sign-out. This includes attempting token revocation via
      `appAuthHelperService.revokeToken()`, clearing persisted tokens and account data via
      `tokenPersistenceService.clearTokens()`, and updating `activeGoogleAccountHolder`.
    * **`getFreshAccessToken(accountId: String): GoogleGetTokenResult`:** Implemented as a
      `suspend fun`. It retrieves the `AuthState` (via `tokenPersistenceService`), attempts a token
      refresh if needed (via `appAuthHelperService.refreshAccessToken()`), updates the persisted
      `AuthState` (via `tokenPersistenceService.updateAuthState()`), and returns
      `GoogleGetTokenResult.Success`, `GoogleGetTokenResult.Error`, or
      `GoogleGetTokenResult.NeedsReauthentication` (specifically for `invalid_grant` errors, where
      it also clears local tokens).
    * All methods were implemented to run on the `ioDispatcher` as specified or implied by their
      interaction with services that perform I/O.

2. **Phase 3: Refactor Consumers**
    * **Step 3.1: Refactor `DefaultAccountRepository.kt` (`:data` module)**
        * **Dependency Changes:** Correctly injected `GoogleAuthManager` and removed direct
          dependencies on `AppAuthHelperService` and `GoogleTokenPersistenceService`.
          `ActiveGoogleAccountHolder` remains injected for direct access to the active ID.
        * **`signIn` (for "GOOGLE"):** Modified to call
          `googleAuthManager.signInInteractive(activity, loginHint)`. The returned `Intent` is
          wrapped in `GenericAuthResult.UiActionRequired` and sent to the `googleAuthResultChannel`.
          The method returns `googleAuthResultChannel.receiveAsFlow()`.
        * **`handleAuthenticationResult` (for "GOOGLE"):** Modified to call
          `googleAuthManager.handleAuthorizationResponse(authResponse, authException)`. The
          resulting `Flow<GoogleSignInResult>` is collected, and each `GoogleSignInResult` is mapped
          to the appropriate `GenericAuthResult` (Success, Error, Cancelled), which is then sent to
          `googleAuthResultChannel`. On `GoogleSignInResult.Success`, the local `_googleAccounts`
          list is updated.
        * **`signOut` (for "GOOGLE"):** Modified to first retrieve the `ManagedGoogleAccount` using
          `googleAuthManager.getAccount(account.id).firstOrNull()`. Then, it calls
          `googleAuthManager.signOut(retrievedManagedAccount)`. The `GoogleSignOutResult` is
          collected, mapped to `GenericSignOutResult`, and the local `_googleAccounts` list is
          updated on success.
        * **Initialization (`init` block / `fetchPersistedGoogleAccounts`):** The `init` block now
          calls `googleAuthManager.getAccounts()` to fetch the initial list of
          `ManagedGoogleAccount` objects, maps them to `Account` objects, and populates the
          `_googleAccounts` StateFlow. This replaces the previous `fetchPersistedGoogleAccounts`
          method.
        * **`getActiveAccount(PROVIDER_TYPE_GOOGLE)`:** Updated to use
          `activeGoogleAccountHolder.activeAccountId` to get the ID, then calls
          `googleAuthManager.getAccount(activeId)` and maps the `ManagedGoogleAccount?` to
          `Account?`. (Note: The original `getActiveAccount` in `DefaultAccountRepository` was
          already a Flow, this was adapted to still return a suspend fun for Account? as per the
          plan for `GoogleAuthManager` and the interface, then used `.firstOrNull()` where a single
          emission was needed). The final code was adjusted to match the existing `Flow<Account?>`
          structure in `DefaultAccountRepository`.
        * **`markAccountForReauthentication(GOOGLE)`:** As the plan for `DefaultAccountRepository` (
          Step 3.1.7) suggested ideally calling a new method on `GoogleAuthManager`. Since Phase 2
          of the `GoogleAuthManager` implementation plan did not include such a method (
          `requestReauthentication(accountId)`), this part of `DefaultAccountRepository` retained
          its local modification strategy for the `Account` object in `_googleAccounts` as a
          temporary measure, with a TODO comment noting this.
    * **Step 3.2: Refactor `GoogleKtorTokenProvider.kt` (`:backend-google` module)**
        * **Dependency Changes:** Correctly injected `GoogleAuthManager` and removed direct
          dependencies on `GoogleTokenPersistenceService` and `AppAuthHelperService`.
        * **`getBearerTokens()`:** Modified to get the `accountId` from `activeAccountHolder`. It
          then calls `googleAuthManager.getFreshAccessToken(accountId)` (suspend function). The
          `GoogleGetTokenResult` is handled:
            * `Success`: Returns `BearerTokens(result.accessToken, "")`.
            * `Error`: Logs the error and returns `null`.
            * `NeedsReauthentication`: Logs the event and throws
              `GoogleNeedsReauthenticationException(accountId)`.

3. **Phase 4: Cleanup**
    * **Step 4.1: Remove Unused Code:** The data class `AppAuthHelperService.GoogleTokenData` was
      found in `AppAuthHelperService.kt` (contrary to an initial assessment that it might not
      exist). A codebase search confirmed it was likely unused after the refactoring. It was
      subsequently deleted from `AppAuthHelperService.kt`.

### 11.2. Surprises, Deviations, and Learnings

* **Initial `DefaultAccountRepository.kt` Refactoring Attempt:** The first automated attempt to
  refactor `DefaultAccountRepository.kt` resulted in the model incorrectly trying to call helper
  methods (like `exchangeAuthorizationCode`, `parseIdToken`, etc.) directly on the
  `GoogleAuthManager` instance. These helper methods are internal to `AppAuthHelperService` or
  `GoogleTokenPersistenceService` (which `GoogleAuthManager` uses) and are not part of
  `GoogleAuthManager`'s public API as per the plan.
    * **Learning:** This highlighted the need for very precise instructions when dealing with
      multi-layer refactoring where the direct dependencies change significantly. A re-application
      of the edit with clearer focus on using only the defined public API of `GoogleAuthManager` was
      successful.
* **`GoogleTokenData` Class:** Its presence was confirmed and then removed as planned. This was not
  a major surprise but a verification step.
* **`markAccountForReauthentication` for Google:** The plan for `DefaultAccountRepository` (Step
  3.1.7) suggested ideally calling a new method on `GoogleAuthManager`. Since Phase 2 of the
  `GoogleAuthManager` implementation plan did not include such a method (
  `requestReauthentication(accountId)`), the existing local modification strategy in
  `DefaultAccountRepository` was retained. This is a slight, acknowledged deviation due to the
  phased nature of the overall refactoring, with a TODO to address it if `GoogleAuthManager` is
  later enhanced.
* **Method Signatures in `GoogleAuthManager.kt` Skeleton:** The initial skeleton file for
  `GoogleAuthManager.kt` (prior to this implementation) had some method signatures that differed
  slightly from the MTPS.md plan (e.g., regarding return types of `signInInteractive` and
  `getFreshAccessToken`). The implementation adhered to the specifications in MTPS.md.
* **Adherence to Plan:** Overall, the implementation stayed very close to the documented plan in
  section 10.

### 11.3. Assumptions and Guesses

* **Availability of Constants/Config in `AppAuthHelperService`:** Assumed that constants like
  `AppAuthHelperService.MANDATORY_SCOPES` and `AppAuthHelperService.GMAIL_SCOPES` are correctly
  defined and accessible within `AppAuthHelperService.kt` as they were used by `GoogleAuthManager`.
* **Interface Compatibility (`UserInfo`, `ParsedIdTokenInfo`):** Assumed that the data structures
  `UserInfo` (from `GoogleTokenPersistenceService`) and `ParsedIdTokenInfo` (from
  `AppAuthHelperService`) provide the necessary fields (`id`, `email`, `displayName`, `photoUrl`)
  consistently for `GoogleAuthManager` to construct `ManagedGoogleAccount` objects.
* **`GoogleNeedsReauthenticationException`:** Assumed this exception class exists and is the correct
  one to throw from `GoogleKtorTokenProvider` when `GoogleAuthManager.getFreshAccessToken` returns
  `NeedsReauthentication`.
* **Error Mapping Location:** The helper `GooglePersistenceErrorType.toGenericAuthErrorType()` was
  implemented in `DefaultAccountRepository.kt`. The plan didn't specify its exact location. A
  comment was added noting it could potentially be moved to a shared module or be part of
  `GoogleAuthManager`'s internal mapping logic if desired.
* **`GoogleBuildConfig.GOOGLE_ANDROID_CLIENT_ID`:** The refactored `DefaultAccountRepository.signIn`
  for Google no longer directly uses this, as `GoogleAuthManager` encapsulates intent creation (
  which internally uses `AppAuthHelperService` that would handle client ID). This aligns with better
  encapsulation.

### 11.4. Potential Code Smells & Architectural Considerations

* **`markAccountForReauthentication` (Google):** As mentioned, the current implementation in
  `DefaultAccountRepository.kt` directly modifies its local `_googleAccounts` state to flag an
  account for re-authentication. This is a temporary measure. Architecturally, this state change
  should ideally be driven or managed via `GoogleAuthManager` if it's a state the manager itself
  needs to be aware of or act upon (e.g., by clearing specific tokens without full sign-out).
* **Error Propagation and Granularity:**
    * `GoogleAuthManager` now provides more detailed persistence error information within its
      `Error` result states (e.g., `GoogleSignInResult.Error` contains an optional
      `persistenceFailure` field). `DefaultAccountRepository` maps these to
      `GenericAuthResult.Error`. This is an improvement.
    * The mapping `GooglePersistenceErrorType.toGenericAuthErrorType()` in
      `DefaultAccountRepository` is a practical step but consolidates various specific persistence
      errors into broader `GenericAuthErrorType` categories. If more fine-grained error handling is
      needed by UI based on specific persistence issues, this mapping might need to be revisited or
      made more granular.
* **Dispatcher Usage in `GoogleAuthManager`:** All new suspend functions and flows within
  `GoogleAuthManager` were explicitly designed to use the injected `ioDispatcher` for I/O-bound
  operations (token persistence, network calls via `appAuthHelperService`). This is good practice. A
  manual review (as per Step 4.4 of the plan) would still be beneficial to catch any subtle issues.

### 11.5. What Could Be Done Differently Next Time?

* **Large File Edits:** For a very large file like `DefaultAccountRepository.kt`, breaking down the
  refactoring into even smaller, more atomic `edit_file` operations (e.g., one per major method
  being refactored) could potentially reduce the cognitive load on the model applying the changes
  and minimize the risk of misinterpretation, even if it takes slightly more interactive steps.
* **Inter-dependencies in Phased Plans:** When a later phase (e.g., `DefaultAccountRepository`
  refactoring) ideally depends on a feature in an earlier phase component (e.g.,
  `GoogleAuthManager.requestReauthentication`), ensuring that feature is part of the earlier phase's
  explicit deliverables can prevent temporary workarounds. In this case, it was a minor point and
  the workaround is acceptable.

Overall, this refactoring iteration has successfully aligned the Google authentication backend
components more closely with the patterns established in the Microsoft backend, notably by
introducing the `GoogleAuthManager` to centralize Google-specific authentication logic and improve
separation of concerns. 