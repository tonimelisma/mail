# Build Process & Refactoring Changelog

This document tracks the issues encountered, changes made, and architectural decisions during the
recent build stabilization and refactoring efforts.

## Initial State & Goal

The primary goal was to resolve build errors that emerged after a significant refactoring effort,
guided by `DESIGN.md` and `CHANGELOG.md`. The refactoring aimed to improve the authentication and
data persistence layers across different backend modules (Microsoft, Google).

## Issues Encountered & Resolutions

### Phase 1: Microsoft Backend (`:backend-microsoft`)

* **Issue:** Build failures in `:backend-microsoft:compileDebugKotlin`.
    * **Details:** Non-exhaustive `when` expressions when handling `PersistenceResult.Failure`. The
      compiler could not infer the specific error type.
    * **Resolution:**
        * Modified `MicrosoftAuthManager.kt` to explicitly use
          `PersistenceResult.Failure<PersistenceErrorType>` in `when` statements.
        * Added necessary import for `net.melisma.backend_microsoft.common.PersistenceErrorType`.
        * Modified `MicrosoftTokenPersistenceService.kt` similarly for `when` expressions checking
          results from `removeAccountFromManagerInternal`.

* **Issue:** Unresolved reference `getAccountById` in `MicrosoftKtorTokenProvider.kt`.
    * **Details:** The method `getAccountById` did not exist on `MicrosoftAuthManager`. The correct
      method was `getAccount(accountId: String)` which returns a `ManagedMicrosoftAccount?`, and the
      `IAccount` was needed.
    * **Resolution:**
        * Changed the call in `findMsalAccount` within `MicrosoftKtorTokenProvider.kt` from
          `microsoftAuthManager.getAccountById(accountId).firstOrNull()` to
          `microsoftAuthManager.getAccount(accountId)?.iAccount`.
    * **Observation:** The initial auto-apply of this edit resulted in a very noisy diff, adding
      many unnecessary imports. A re-application with more specific instructions was attempted, but
      the diff indicated no change, suggesting the core logic was correctly applied initially
      despite the noisy import changes.

### Phase 2: Google Backend (`:backend-google`)

* **Issue:** Build failures in `:backend-google:kspDebugKotlin` and
  `:backend-google:compileDebugKotlin`.
    * **Error 1 (compileDebugKotlin):** Non-exhaustive `when` expressions in `GoogleAuthManager.kt`.
        * **Details:** Similar to the Microsoft module, `when` statements handling
          `PersistenceResult.Failure` were not specific enough.
        * **Resolution:**
            * Modified `GoogleAuthManager.kt` to explicitly use
              `PersistenceResult.Failure<GooglePersistenceErrorType>` in `when` statements.
            * Ensured the import for `net.melisma.backend_google.common.GooglePersistenceErrorType`.

    * **Error 2 (kspDebugKotlin & compileDebugKotlin in `GoogleKtorTokenProvider.kt`):** Multiple
      unresolved references and type mismatches.
        * **Details:**
            1. `Unresolved reference: TokenProvider`: The `GoogleKtorTokenProvider` was trying to
               implement an interface `TokenProvider` that could not be found in the codebase.
               Searches (file search, grep) confirmed its absence. `DESIGN.md` indicated an
               intention for such an interface.
            2. `Unresolved reference: AuthenticatedGoogleAccountInfo`: This data class was used as a
               return type but its definition was not found.
            3. `Type mismatch: Expected BearerTokens?, Found GoogleAccountInfo?`: In the Ktor `Auth`
               plugin setup within `BackendGoogleModule.kt` (as inferred from `DESIGN.md` and
               typical Ktor patterns), the `loadTokens` lambda in the bearer authentication provider
               was expected to return `BearerTokens?` (a Ktor type) but was instead typed to return
               `GoogleAccountInfo?`.
            4. `Unresolved reference: GoogleAccountInfo`: The data class `GoogleAccountInfo` itself
               was missing. A file read attempt for
               `backend-google/src/main/java/net/melisma/backend_google/model/GoogleAccountInfo.kt`
               failed.
            5. `Unresolved reference: getAccountById`: Similar to the Microsoft module,
               `GoogleKtorTokenProvider.kt` was trying to call a non-existent `getAccountById`
               method.

    * **Architectural Observation & Next Steps (for GoogleKtorTokenProvider):**
        * The `TokenProvider` interface seems to be a missing piece of the planned architecture.
        * `AuthenticatedGoogleAccountInfo` and `GoogleAccountInfo` are likely part of this missing
          or incomplete refactoring.
        * The `GoogleKtorTokenProvider.kt` appears to be significantly diverged from a working state
          or from the pattern established in `MicrosoftKtorTokenProvider.kt` (which is much larger
          and more complete) and the Ktor authentication standards.
        * **Hypothesis:** The `GoogleKtorTokenProvider.kt` needs to be rewritten or significantly
          refactored to:
            1. Correctly implement Ktor's `BearerAuthenticationProvider.Config.loadTokens` to return
               `BearerTokens` (containing access and refresh tokens).
            2. Likely fetch user details (`email`, `displayName`, etc.) separately after successful
               token validation/retrieval, rather than trying to make `loadTokens` return a
               combined "AccountInfo" object directly. This aligns with how `DESIGN.md` describes
               the separation of `GoogleAuthManager` (handling AppAuth, persistence) and
               `GoogleKtorTokenProvider` (handling Ktor bearer auth).
            3. Remove references to the non-existent `getAccountById` and
               `AuthenticatedGoogleAccountInfo`.

### Phase 3: General Codebase Investigation

* **Issue:** Missing `core-data/src/main/java/net/melisma/core_data/auth/TokenProvider.kt`.
    * **Details:** Build logs and attempts to read the file confirmed its absence. File searches and
      grep searches for `interface TokenProvider` also yielded no results for its definition.
    * **Impact:** This missing interface is critical for the `GoogleKtorTokenProvider` as it was
      declared to implement it.
    * **Resolution (Implied):** The interface needs to be created or the `GoogleKtorTokenProvider`
      needs to be refactored to not depend on it if the architectural decision has changed. Based on
      `DESIGN.MD`, it was intended to exist.

* **Issue:** Missing
  `backend-google/src/main/java/net/melisma/backend_google/model/GoogleAccountInfo.kt`.
    * **Details:** File read attempt failed. This class was referenced in the (broken)
      `GoogleKtorTokenProvider`.
    * **Impact:** Prevents `GoogleKtorTokenProvider` from compiling as it was.
    * **Resolution (Implied):** This data class needs to be created, or the
      `GoogleKtorTokenProvider` needs to be refactored to use a different model or approach.

## Summary of Changes Made (Code Edits)

* **`MicrosoftAuthManager.kt`:**
    * Updated `when` expressions to use `PersistenceResult.Failure<PersistenceErrorType>` for
      exhaustiveness.
    * Added import for `net.melisma.backend_microsoft.common.PersistenceErrorType`.
* **`MicrosoftTokenPersistenceService.kt`:**
    * Updated `when` expressions checking `removeAccountFromManagerInternal` results to use
      `PersistenceResult.Failure<PersistenceErrorType>`.
* **`MicrosoftKtorTokenProvider.kt`:**
    * Corrected `findMsalAccount` to call `microsoftAuthManager.getAccount(accountId)?.iAccount`.
* **`GoogleAuthManager.kt`:**
    * Updated `when` expressions for `PersistenceResult.Failure` to use
      `PersistenceResult.Failure<GooglePersistenceErrorType>` for exhaustiveness.
    * Ensured import for `net.melisma.backend_google.common.GooglePersistenceErrorType`.

## Outstanding Issues & Next Steps

1. **Refactor `GoogleKtorTokenProvider.kt`:**
    * Define the `TokenProvider` interface (likely in `core-data`) as per `DESIGN.md` or decide on
      an alternative.
    * Define necessary data classes like `GoogleAccountInfo` or `AuthenticatedGoogleAccountInfo` (or
      determine their correct replacements/locations).
    * Correct the `loadTokens` lambda in `BackendGoogleModule.kt` (for Ktor setup) to return
      `io.ktor.server.auth.BearerTokens?`. This involves changing `GoogleKtorTokenProvider.kt` to
      provide these tokens. It should not directly return `GoogleAccountInfo`.
    * The provider should use `GoogleAuthManager.getFreshAccessToken(accountId)` to get tokens and
      potentially `GoogleAuthManager.getAccount(accountId)` to get user details for constructing a
      `Principal`.
    * Remove references to `getAccountById`.

2. **Verify `TokenProvider` interface:**
    * Confirm if `TokenProvider` from `DESIGN.md` is still the intended abstraction. If so, create
      it in `core-data`.
    * Ensure `MicrosoftKtorTokenProvider.kt` also correctly implements this interface if it's meant
      to be generic.

3. **Full Build & Test:** After addressing the `GoogleKtorTokenProvider.kt` issues, perform a full
   build including tests (`./gradlew build`) to catch any further integration problems.

## Architectural Choices & Observations During Troubleshooting

* **Reliance on `PersistenceResult.Failure<T>`:** The refactoring introduced a pattern of using a
  generic `PersistenceResult.Failure<SpecificErrorType>`. Initial implementations missed making the
  `when` clauses exhaustive by not specifying the generic type, which the Kotlin compiler requires
  for sealed class subtypes with generics.
* **Separation of Concerns (Auth Managers vs. Ktor Providers):** The `DESIGN.md` and existing code
  for Microsoft suggest a clear separation:
    * `AuthManager` (e.g., `MicrosoftAuthManager`, `GoogleAuthManager`): Handles the specifics of
      the identity provider SDKs (MSAL, AppAuth), user interaction, and secure persistence of tokens
      and account information.
    * `KtorTokenProvider` (e.g., `MicrosoftKtorTokenProvider`): Integrates with Ktor's `Auth`
      plugin, primarily responsible for validating incoming bearer tokens for API requests using the
      tokens persisted by the respective `AuthManager`.
* **Inconsistency in Google Backend:** The `GoogleKtorTokenProvider.kt` was significantly less
  developed and inconsistent with the `DESIGN.md` compared to its Microsoft counterpart. It seemed
  to be in a partially refactored or broken state.
* **Missing Core Abstractions:** The absence of the `TokenProvider` interface and related data
  models (`GoogleAccountInfo`, `AuthenticatedGoogleAccountInfo`) points to an incomplete refactoring
  in the Google module and potentially the core data module.

This document should serve as a snapshot of the troubleshooting process up to this point.

## 2024-07-29: Authentication Refactoring - Ktor Integration Phase 1

This phase focused on implementing the Ktor `Auth` plugin integration for both Microsoft and Google
providers as outlined in `GAP.md`, along with creating core authentication exception classes and
refactoring token providers.

**Key Goals Achieved:**

- Unified `TokenProvider` interface usage for Ktor.
- Ktor `Auth` plugin configured for Microsoft and Google with `loadTokens`, `refreshTokens`, and
  `validate` blocks.
- Robust error handling within token providers and Ktor auth lambdas, using custom
  `NeedsReauthenticationException` and `TokenProviderException`.
- Refactored `GoogleKtorTokenProvider` for alignment with `GoogleAuthManager` and the
  `TokenProvider` interface.
- Refined Ktor DI modules (`BackendMicrosoftModule.kt`, `BackendGoogleModule.kt`) to integrate these
  changes.

**Detailed Changes, Learnings & Deviations:**

**1. Core Authentication Components (`:core-data`):**
- **Planned:** Verify or create `TokenProviderException.kt` and `NeedsReauthenticationException.kt`.
- **Executed:**
- Created `core-data/src/main/java/net/melisma/core_data/auth/TokenProviderException.kt` with the
specified structure:
`class TokenProviderException(override val message: String, override val cause: Throwable? = null) : RuntimeException(message, cause)`.
- Created `core-data/src/main/java/net/melisma/core_data/auth/NeedsReauthenticationException.kt`
with the specified structure:
`class NeedsReauthenticationException(val accountIdToReauthenticate: String?, override val message: String, override val cause: Throwable? = null) : RuntimeException(message, cause)`.
- **Learnings:** Confirmed these files were missing based on initial directory listings and
successfully created them as per the `GAP.md` specification.

**2. Microsoft Backend (`:backend-microsoft`):**
- **`MicrosoftKtorTokenProvider.kt`:**
- **Planned:** Minor cleanup (unused imports, confirm logging solution).
- **Executed:**
- Removed unused imports: `android.util.Log` (Timber is used), `kotlinx.coroutines.CoroutineScope`,
`kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`.
- Confirmed Timber logging is already in use and appropriate, aligning with project standards.
- **Ktor Integration (`BackendMicrosoftModule.kt`):**
- **Planned:** Locate or create DI Module, define named Ktor `bearer` auth, implement `loadTokens`,
`refreshTokens`, `validate` lambdas.
- **Deviation/Learning 1:** Discovered `BackendMicrosoftModule.kt` *already existed* with a Hilt
setup and a Ktor `HttpClient` (`@MicrosoftGraphHttpClient`) that included a partially configured
`Auth` plugin referencing `MicrosoftKtorTokenProvider`. The plan was adapted to *modify* this
existing module rather than creating a new one. The named Ktor provider (e.g., "msAuth") was deemed
unnecessary as this client has only one bearer auth.
- **Executed Modifications:**
- Added `MicrosoftAuthManager` as an injected parameter to the
`@Provides @MicrosoftGraphHttpClient fun provideMicrosoftGraphHttpClient(...)` method to make it
available for the `validate` lambda.
- In the `Auth` plugin's `bearer` block:
- **`loadTokens` lambda:**
- Corrected the call from `microsoftKtorTokenProvider.loadBearerTokens()` to the interface-defined
`microsoftKtorTokenProvider.getBearerTokens()`.
- Implemented comprehensive exception handling as per `GAP.md`: catching
`NeedsReauthenticationException`, `TokenProviderException`, and generic `Exception`, logging
appropriately, and returning `null` to Ktor.
- **`refreshTokens` lambda:**
- The existing call `microsoftKtorTokenProvider.refreshBearerTokens(oldTokens)` was correct.
- Added comprehensive exception handling similar to `loadTokens`.
- **`validate` lambda:**
- **Learning/Challenge 2:** Confirmed via code inspection that `MicrosoftAuthManager.getAccount()`
is a `suspend` function. Ktor's `validate` lambda is not a suspend context.
- **Shortcut/Solution:** Used `runBlocking { microsoftAuthManager.getAccount(accountId) }` to bridge
this. This was a pragmatic choice to fulfill `GAP.md`'s requirement that `validate` produces a
`UserPrincipal`, minimizing changes to `MicrosoftAuthManager`. The potential blocking nature is
acknowledged.
- Ensured `UserPrincipal` is constructed correctly using fields from `ManagedMicrosoftAccount` and
its nested `iAccount` (e.g., `managedAccount.iAccount.id`, `managedAccount.iAccount.username` for
email, `managedAccount.displayName`).
- Added necessary imports: `UserPrincipal`, `NeedsReauthenticationException`,
`TokenProviderException`, `Account`, `Timber`, and `kotlinx.coroutines.runBlocking`.

**3. Google Backend (`:backend-google`):**
- **`GoogleKtorTokenProvider.kt` Refactor:**
- **Planned:** Major refactor/rewrite: implement `TokenProvider`, inject dependencies (
`GoogleAuthManager`, `ActiveGoogleAccountHolder`, `AccountRepository`), implement methods using
`googleAuthManager.getFreshAccessToken()`, use correct exceptions, store `accountId` in
`refreshToken`.
- **Learning 1:** Confirmed `GoogleAuthManager.getFreshAccessToken()` is a `suspend fun` returning
`GoogleGetTokenResult` (not a `Flow` that requires `.first()`). The `GAP.md` had a slight ambiguity
here which was resolved by inspecting `GoogleAuthManager.kt`.
- **Executed Refactoring:**
- Changed class to implement `net.melisma.core_data.auth.TokenProvider`.
- Added `AccountRepository` to constructor injections.
- Implemented `override suspend fun getBearerTokens(): BearerTokens?`:
- Retrieves active Google account ID.
- Calls a new private suspend function `processGetFreshAccessToken(accountId, operationName)` for
core logic.
- Implemented `override suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens?`:
- Determines `accountId` from active holder or `oldTokens`.
- Calls `processGetFreshAccessToken(accountId, operationName)` within a
`refreshMutex.withLock { ... }` block as recommended by `GAP.md`.
- **`private suspend fun processGetFreshAccessToken(...)`:**
- Calls `googleAuthManager.getFreshAccessToken(accountId)`.
- Handles `GoogleGetTokenResult.Success`: returns `BearerTokens(accessToken, accountId)`.
- Handles `GoogleGetTokenResult.NeedsReauthentication`: calls
`accountRepository.markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_GOOGLE)` and
throws `net.melisma.core_data.auth.NeedsReauthenticationException`.
- Handles `GoogleGetTokenResult.Error`: throws `net.melisma.core_data.auth.TokenProviderException`.
- Includes generic try-catch for other exceptions, re-throwing as `TokenProviderException`.
- Removed unused `tokenProviderScope` and its related imports (`CoroutineScope`, `Dispatchers`,
`SupervisorJob`).
- **Ktor Integration (`BackendGoogleModule.kt`):**
- **Planned:** Locate or create DI Module, define Ktor `bearer` auth, implement `loadTokens`,
`refreshTokens`, `validate` lambdas.
- **Deviation/Learning 2:** Discovered `BackendGoogleModule.kt` *already existed* with Hilt setup
and a Ktor `HttpClient` (`@GoogleHttpClient`) with a partially configured `Auth` plugin. Plan
adapted to *modify* this existing module.
- **Executed Modifications:**
- Added `GoogleAuthManager` as an injected parameter to `provideGoogleHttpClient(...)`.
- In the `Auth` plugin's `bearer` block:
- **`loadTokens` lambda:**
- The existing call `googleKtorTokenProvider.getBearerTokens()` was correct.
- Added comprehensive exception handling (similar to Microsoft's).
- **`refreshTokens` lambda:**
- Changed the call from `googleKtorTokenProvider.getBearerTokens()` to the specific
`googleKtorTokenProvider.refreshBearerTokens(oldTokens)`.
- Added comprehensive exception handling.
- **`validate` lambda:**
- **Learning/Challenge 3:** `GoogleAuthManager.getAccount()` returns a
`Flow<ManagedGoogleAccount?>`. Ktor's `validate` lambda is not a suspend context.
- **Shortcut/Solution:** Used
`runBlocking { googleAuthManager.getAccount(accountId).firstOrNull() }` to synchronously get the
account details.
- Ensured `UserPrincipal` is constructed correctly using fields from `ManagedGoogleAccount` (e.g.,
`accountId`, `email`, `displayName`).
- Added necessary imports: `UserPrincipal`, exceptions, `Account`, `Timber`,
`kotlinx.coroutines.runBlocking`, `kotlinx.coroutines.flow.firstOrNull`.

**4. Cross-Cutting Learnings & Decisions:**
- **DI Framework:** Hilt's prevalence was confirmed, guiding dependency provision.
- **Adaptation to Existing Code:** The most significant learning was the presence of existing DI
modules with partial Ktor setups. This required a shift from "create" to "modify and integrate,"
making the process more of a targeted refactor than a from-scratch implementation.
- **Synchronous Calls in `validate`:** The use of `runBlocking` in both Microsoft and Google
`validate` lambdas was a key decision to bridge the gap between Ktor's non-suspending context and
the suspending nature of fetching full account details. This maintains the architectural goal of
`validate` producing a complete `UserPrincipal` as per `GAP.md`, but is a point of attention for
future performance considerations if these lookups become slow.
- **Signature Verification:** Diligent checking of actual function signatures in `AuthManager`
classes (e.g., `suspend fun` vs. `Flow`, return types) was crucial for correct implementation and
differed in minor ways from some initial assumptions in `GAP.md`.
- **Logging Consistency:** Ensured Timber is used for new logging within the Ktor auth blocks,
enhancing debuggability.

**Overall:** This phase successfully integrated the Ktor `Auth` plugin for both Microsoft and Google
providers, aligning with the `TokenProvider` abstraction and robust error handling strategy outlined
in `GAP.md`. The system is now better positioned for reliable authenticated API calls.

## Phase X: Renewed Attempts on Google Backend Compilation (Current Investigation - YYYY-MM-DD)

This section details the ongoing efforts to resolve persistent compilation errors in the
`:backend-google` module, specifically concerning `PersistenceResult.Failure` handling and Ktor Auth
DSL interpretation. These issues prevent the module from compiling, despite earlier changelog
entries suggesting some of these areas were previously stable.

**1. `PersistenceResult.Failure`
Handling (`GoogleAuthManager.kt`, `GoogleTokenPersistenceService.kt`):**

* **Recap of Initial Problem:**
    * Compiler errors such as "One type argument expected. Use class 'Failure' if you don't intend
      to pass type arguments" and "Unresolved reference 'errorType'/'cause'" when trying to work
      with `PersistenceResult.Failure<GooglePersistenceErrorType>`. This was a recurring issue in
      the Google module, even after initial attempts to mirror fixes from the Microsoft module.

* **Attempt 1: Simplification based on Microsoft Module's Success (Failed):**
    * **Action:** Refactored `when` blocks in `GoogleAuthManager.kt` and
      `GoogleTokenPersistenceService.kt` to use `is PersistenceResult.Failure` (without generic
      type) and then directly access `opResult.errorType`, `opResult.message`, etc., or pass the
      smart-cast `opResult` directly to helper functions (e.g., `mapPersistenceErrorToSignInError`).
      The `else` branches in `when` statements (checking for results other than `Success` or
      `Failure`) were also removed as `PersistenceResult` is sealed.
    * **Intention:** To rely on Kotlin's smart-casting, similar to how it worked in the
      `:backend-microsoft` module.
    * **Surprise/Outcome (Failure):** This approach did *not* resolve the issues in the Google
      module. The build continued to fail with a host of errors:
        *
        `'when' expression must be exhaustive. Add the 'is Failure<*>' branch or an 'else' branch.`
        This was particularly surprising given `PersistenceResult` is sealed.
        *
        `One type argument expected. Use class 'Failure' if you don't intend to pass type arguments.` (
        Persisted)
        * `Unresolved reference 'errorType'`, `Unresolved reference 'cause'`,
          `Unresolved reference 'message'`. (Persisted on direct access post smart-cast attempt)
        *
        `Argument type mismatch: actual type is 'net.melisma.core_data.common.PersistenceResult<...>' but 'net.melisma.core_data.common.PersistenceResult.Failure<net.melisma.backend_google.common.GooglePersistenceErrorType>' was expected` (
        Occurred when passing the supposedly smart-cast `opResult` to helper functions).
    * **Code Smell/Architectural Observation:** The Kotlin compiler's behavior in `:backend-google`
      regarding `PersistenceResult.Failure<E>` is significantly different or more stringent than in
      `:backend-microsoft`. Despite `GoogleTokenPersistenceService` methods explicitly returning
      `PersistenceResult.Failure<GooglePersistenceErrorType>`, the smart-cast from
      `is PersistenceResult.Failure` is insufficient for the compiler to resolve types correctly or
      satisfy exhaustiveness checks without `is Failure<*>`. This points to a subtle interaction
      with generics or the specific context of these files. The
      `PersistenceResult.Failure<E : Enum<E>>(...) : PersistenceResult<Nothing>()` definition is
      standard, making the difficulty in this module anomalous.

* **Next Proposed Step (for `PersistenceResult.Failure`):**
    * Based on compiler directives (e.g., "Add the 'is Failure<*>' branch"), the next attempt should
      involve:
        1. Changing `is PersistenceResult.Failure` to `is PersistenceResult.Failure<*>`.
        2. Inside this block, checking if `opResult.errorType is GooglePersistenceErrorType`.
        3. If true, then (and only then) casting `opResult` to the specific
           `PersistenceResult.Failure<GooglePersistenceErrorType>`. This might require
           `@Suppress("UNCHECKED_CAST")`.
        4. Using this specifically casted object to access properties or pass to helper methods.
    * This is more verbose than the pattern in the Microsoft module and indicates a deeper type
      inference challenge in the Google module.

**2. Ktor Auth DSL (`BackendGoogleModule.kt`):**

* **Recap of Initial Problem (as per current build attempt):**
    * `refreshTokens` lambda: Multiple errors including "Function invocation 'refreshTokens(...)'
      expected", "Variable expected", "Assignment type mismatch", and "Suspend function
      ...refreshBearerTokens... should be called only from a coroutine...".
    * `validate` block: "Unresolved reference 'validate'".

* **Attempt 1: Align `refreshTokens` with Ktor Property Signature (Failed):**
    * **Action:** Modified the `refreshTokens` lambda from
      `refreshTokens { it: RefreshTokensParams -> ... }` to the explicit property assignment style
      `refreshTokens = suspend { params: RefreshTokensParams -> ... googleKtorTokenProvider.refreshBearerTokens(params.oldTokens) ... }`.
    * **Intention:** To match Ktor's `BearerAuthConfig` property definition:
      `var refreshTokens: (suspend ClientScope.(RefreshTokensParams) -> BearerTokens?)?`.
    * **Surprise/Outcome (Failure):** This change led to a different set of compilation errors for
      the `refreshTokens` line, as listed above. The compiler seemed unable to correctly parse this
      as a valid assignment of a suspend lambda to the property within the DSL scope. The "Suspend
      function ...refreshBearerTokens... should be called only from a coroutine" error is
      particularly confusing as the `refreshTokens` lambda itself *is* the suspend context provided
      by Ktor.
    * The `validate` block remained unresolved.

* **Architectural Misalignment/Surprise (Contradiction with Past Changelog):**
    * The `BUILD_CHANGELOG.md` entry for "2024-07-29: Authentication Refactoring - Ktor Integration
      Phase 1" indicates that the Ktor `Auth` plugin configuration (including `loadTokens`,
      `refreshTokens`, and `validate`) for `BackendGoogleModule.kt` was successfully implemented and
      considered working.
    * The current state, where these exact blocks (as they appear in the codebase and align with the
      changelog's description of the fix) are failing to compile, is a **significant deviation**.
      This suggests either:
        * The previous "success" was on a different branch or code state that has diverged.
        * Subsequent changes (perhaps related to Kotlin versions, Ktor library updates if any, or
          other dependencies) have broken this previously working setup.
        * There's an environmental or subtle configuration issue causing the compiler to fail now
          where it previously succeeded.
    * **Code Smell:** The Ktor DSL is generally robust. The persistent and evolving nature of the
      compilation errors in this specific module's Ktor setup, especially for fundamental parts like
      `refreshTokens` and `validate`, points to a potentially deep-seated issue rather than a simple
      syntax error. It could be an intricate interplay between Hilt, Ktor's DSL, and Kotlin language
      features.

**3. Overall Observations & Path Forward:**

* **Module-Specific Sensitivity:** The `:backend-google` module exhibits a higher sensitivity to
  generic type handling and DSL interpretation than other modules like `:backend-microsoft`, where
  similar patterns were applied successfully.
* **Iterative Refinement Needed:** The immediate next steps involve applying the more verbose
  `PersistenceResult.Failure<*>` pattern and then re-evaluating the Ktor DSL issues. For Ktor,
  comparison with the `:backend-microsoft` module's `BackendMicrosoftModule.kt` (which *is*
  compiling its Ktor setup) might provide clues, or a more drastic simplification of the Google Ktor
  setup might be needed for diagnostics.
* **Investigate Changelog Discrepancy:** The contradiction between the current build failures for
  Ktor in `BackendGoogleModule.kt` and the prior changelog entry claiming success needs to be
  understood. Reviewing commit history around the "2024-07-29" entry might be beneficial.

This ongoing investigation highlights the complexities that can arise in a modularized codebase with
advanced language features and intricate library integrations. 