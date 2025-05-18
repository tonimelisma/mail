# Outstanding Refactoring Tasks & Open Questions

This document lists outstanding tasks, open architectural questions, and areas for future refinement
related to the recent authentication and data layer refactoring.

**Exclusions:** This list explicitly excludes:

- Implementation of pending `MainViewModel.kt` methods (tracked separately).
- Unit test creation or fixing (tracked separately).
- General documentation updates (e.g., `DESIGN.MD`, tracked separately).

## 1. `:backend-microsoft` (`MicrosoftAuthManager.kt`)

### 1.1. Granular Error Handling in Account Persistence

- **File:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt`
- **Context:** The `saveAccountInfoToAccountManager` method persists multiple pieces of account
  information using `AccountManager.setUserData()`.
- **Issue/Question:** `ALT.MD` noted that error handling for these `setUserData` calls could be more
  granular. For instance, if encrypting/setting one piece of data (e.g., access token) fails after
  another (e.g., ID token) has succeeded.
- **Current State:** The current implementation mimics the original class's error handling. A
  failure during persistence might lead to a partial state being saved or a rollback of the entire
  account addition if it was new.
- **Task/Consideration:** Review if more specific error handling or rollback logic is required for
  individual `setUserData` failures within a single `saveAccountInfoToAccountManager` operation.
  Assess the risk and impact of partial data persistence.

### 1.2. `acquireTokenSilent` Discovering Accounts Not in `AccountManager`

- **File:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt`
- **Context:** `ALT.MD` identified a low-risk edge case.
- **Issue/Question:** Could `acquireTokenSilent` (which interacts directly with MSAL) "discover" or
  succeed for an account that, for some reason (e.g., external modification, prior error), is not
  fully represented or known to our `AccountManager` persistence layer?
- **Current State:** Believed to be low risk. MSAL itself is the source of truth for accounts it
  manages. The `MicrosoftAuthManager` aims to synchronize this with `AccountManager`.
- **Task/Consideration:** Briefly re-evaluate if this scenario poses any significant practical
  issues or if the current synchronization logic (saving after successful interactive sign-in,
  loading accounts on init) is sufficient.

### 1.3. Concurrency Guarantees for Persistence Operations

- **File:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt`
- **Context:** `AccountManager` operations performed by `MicrosoftAuthManager` are typically
  dispatched to an IO context.
- **Issue/Question:** `ALT.MD` raised that if stricter completion guarantees or atomicity were
  needed across multiple persistence steps, the current asynchronous nature might need adjustments.
- **Current State:** Operations are generally independent calls to `AccountManager`.
- **Task/Consideration:** Determine if the current level of concurrency control is adequate or if
  specific sequences of persistence operations require more explicit synchronization or
  transactional behavior (if feasible with `AccountManager`).

### 1.4. Visibility of `PersistedMicrosoftAccount` Data Class

- **File:**
  `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt`
- **Context:** The `PersistedMicrosoftAccount` data class is currently file-level private within
  `MicrosoftAuthManager.kt`.
- **Issue/Question:** `ALT.MD` noted this could be a top-level public class if needed more broadly
  by other components within the `:backend-microsoft` module.
- **Current State:** Appears to be used only internally by `MicrosoftAuthManager`.
- **Task/Consideration:** Confirm if current visibility is still appropriate. If future needs arise
  for this data class elsewhere in the module, revisit making it a public top-level class.

## 2. `:data` (`DefaultAccountRepository.kt`)

### 2.1. `init` Block Account Merging Logic

- **File:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt` (lines ~
  81-85)
- **Context:** The `init` block contains a `TODO` comment regarding the initial merging of Microsoft
  and Google accounts:
  ```kotlin
  // TODO: Properly initialize MS accounts and merge with Google accounts
  // val currentMsAccounts = microsoftAuthManager.accounts // How to get MS accounts for init?
  // _accounts.value = mapToGenericAccounts(currentMsAccounts, persistedGoogleAccounts) // mapToGenericAccounts needs review for MS
  _accounts.value = persistedGoogleAccounts // Simplified for now
  ```
- **Issue/Question:** Is the "simplified for now" approach sufficient, or does the initial,
  synchronous merge still require a more direct implementation?
- **Current State:** The `init` block sets up collectors for
  `microsoftAccountRepository.getAccounts()` and the internal `_googleAccounts`. Both collectors
  trigger `updateCombinedAccountsAndOverallAuthState()`, which handles the merging and updates the
  main `_accounts` flow.
- **Task/Consideration:** Evaluate if the current reactive merging via
  `updateCombinedAccountsAndOverallAuthState()` adequately covers the initial state. The `TODO`
  might be addressed by the existing reactive flow, but a confirmation would be good.

## 3. Code Cleanup and Minor Refinements (General)

### 3.1. Obsolete Commented-Out Code

- **Files:** Primarily `DefaultAccountRepository.kt`, but potentially others refactored.
- **Issue/Question:** Many old methods and logic blocks were commented out during the refactoring.
- **Task/Consideration:** Review and remove these commented-out blocks if they are confirmed to be
  fully obsolete and their logic has been successfully migrated or replaced by the new architecture.
  This improves code readability and maintainability.

### 3.2. General `TODO` Comments

- **Files:** Across the codebase in refactored modules.
- **Issue/Question:** Are there any other minor `TODO` comments left during the refactoring that are
  not covered by the major items above?
- **Task/Consideration:** A general pass to find and address or re-evaluate any remaining minor
  `TODOs`.

## 4. `androidx.credentials.CredentialManager` Cleanup

- **Files:**
  - `backend-google/build.gradle.kts` (test dependencies)
  - `DESIGN2.md` (and potentially `DESIGN.MD` if it's still relevant)
  - `backend-google/src/main/AndroidManifest.xml`
  - Any other documentation or code comments mentioning its direct use for Google Sign-In.
- **Context:** Previous design documents (`DESIGN2.md`, `DESIGN.md`) and some test configurations
  suggested the use of `androidx.credentials.CredentialManager` for the initial Google Sign-In flow.
- **Issue/Question:** Code investigation revealed that `CredentialManager` is not actively used in
  the runtime application logic for Google Sign-In. Its presence is primarily limited to test
  dependencies in `backend-google/build.gradle.kts` and mentions in documentation. The primary
  Google authentication mechanism is AppAuth.
- **Task/Consideration:**
  - Verify if the `androidx.credentials` and `com.google.android.libraries.identity.googleid`
    `testImplementation` dependencies in `backend-google/build.gradle.kts` are actively used by any
    tests. If not, remove them. If they are used (e.g., for testing interactions with Android system
    components), ensure comments clarify they are not part of the main application authentication
    flow.
  - Update all relevant design documents (especially `DESIGN2.md`, and `DESIGN.md` if still in use)
    to accurately reflect that AppAuth is the mechanism for the Google OAuth flow. Remove mentions
    of `CredentialManager` being used for initial Google Sign-In ID token retrieval in the
    production code.
  - Search the codebase for any other comments or configurations that imply `CredentialManager` has
    an active role in Google sign-in and remove or clarify these references.
  - Evaluate the necessity of the `android.permission.CREDENTIAL_MANAGER_SERVICE` permission in
    `backend-google/src/main/AndroidManifest.xml`. If `CredentialManager` is confirmed to be unused
    in the application's runtime, this permission might no longer be required. (Double-check if
    AppAuth or other libraries might require it indirectly, although this specific permission is
    typically for direct `CredentialManager` API usage). 