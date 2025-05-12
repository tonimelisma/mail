# Melisma Mail - Unified API & UI Integration - Next Steps

**Date:** May 11, 2025
**Last Updated:** May 11, 2025

**Objective:** This document outlines the remaining tasks to fully integrate both Google (AppAuth)
and Microsoft (MSAL) accounts, leveraging the now modernized `MailApiService` (which does not
require explicit access token parameters) and Ktor `Auth` plugins in both `GmailApiHelper` and
`GraphApiHelper`.

**Current Status - Confirmed:**

* **`MailApiService.kt`:** Interface methods updated to **not** require `accessToken` parameters.
* **`GmailApiHelper.kt`:**
    * Implements the updated `MailApiService`.
    * Uses a Ktor `HttpClient` with the `Auth` plugin correctly configured for Google (AppAuth
      tokens via a `GoogleKtorTokenProvider`).
    * API methods do not manually handle or expect `accessToken` parameters.
* **`GraphApiHelper.kt`:**
    * Implements the updated `MailApiService`.
    * Uses a Ktor `HttpClient` with the `Auth` plugin correctly configured for Microsoft (MSAL
      tokens via a `MicrosoftKtorTokenProvider`).
    * API methods do not manually handle or expect `accessToken` parameters.
* **`DefaultAccountRepository.kt`:**
    * Correctly uses `AppAuthHelperService` and `GoogleTokenPersistenceService` for the Google
      AppAuth flow initiation and token persistence.
    * Google sign-out is comprehensive.
* **`DefaultFolderRepository.kt` & `DefaultMessageRepository.kt`:**
    * Updated to call the token-less `MailApiService` methods for the **Google** provider path.
    * **Still require updates** to do the same for the **Microsoft** provider path (currently,
      `DefaultFolderRepository` throws an `UnsupportedOperationException` for non-Google providers
      when trying to call the token-less API methods).

---
## Implementation Plan: Next Steps
---

### **Phase 1: Finalize Repository Layer for Unified `MailApiService`**
---

**Context:** The data repositories (`DefaultFolderRepository`, `DefaultMessageRepository`) need to
be updated to consistently call the `MailApiService` methods (which no longer take `accessToken`)
for *both* Google and Microsoft providers. The `TokenProvider` interface and its implementations
might become obsolete for this specific use case.

**Task 1.1: Update `DefaultFolderRepository.kt` for Microsoft Provider**

* **Goal:** Modify `DefaultFolderRepository.launchFolderFetchJob` (and any similar methods) to call
  `mailApiService.getMailFolders()` (and other methods) directly for the "MS" provider, without
  attempting to fetch or pass an access token. The Ktor `Auth` plugin in the Microsoft-specific
  `HttpClient` will handle authentication.
* **File to Modify:** `data/src/main/java/net/melisma/data/repository/DefaultFolderRepository.kt`
* **Detailed Steps:**
    1. In `launchFolderFetchJob` (and any similar data-fetching methods):
        * Locate the conditional logic for `providerType`.
        * Currently, your `DefaultFolderRepository` has a line like:
            ```kotlin
            val foldersResult = if (providerType == "GOOGLE") {
                mailApiService.getMailFolders()
            } else {
                // This part needs to be updated for Microsoft
                val accessToken = tokenResult.getOrThrow() // This tokenResult is from the old TokenProvider
                // The line below would now cause a compile error or was commented out/changed:
                // mailApiService.getMailFolders(accessToken)
                throw UnsupportedOperationException("Non-Google providers are temporarily unsupported until they are updated to conform to the new MailApiService interface")
            }
            ```
        * **Modify this logic:**
            ```kotlin
            // ... inside launchFolderFetchJob, after getting mailApiService
            val foldersResult = mailApiService.getMailFolders() // Call directly for ALL providers now
            // ... rest of the logic to handle foldersResult
            ```
        * **Remove `TokenProvider` Usage for API Calls:**
            * The lines `val tokenProvider = tokenProviders[providerType]` and the subsequent
              `tokenProvider.getAccessToken(...)` call within these data fetching jobs are **no
              longer needed** for calling `MailApiService` methods for either Google or Microsoft,
              as Ktor handles the tokens.
            * You can remove the `tokenProviders` map injection from `DefaultFolderRepository` if
              this was its only use. If other parts of the repository use `TokenProvider` for direct
              token access (not for `MailApiService` calls), then keep it.
            * Remove the `if (tokenResult.isSuccess)` block that wraps the `mailApiService` call, as
              the call is now direct. Error handling for token acquisition is now managed by the
              Ktor `Auth` plugin and will manifest as Ktor exceptions if a token cannot be loaded or
              refreshed by the respective `KtorTokenProvider`. These Ktor exceptions should be
              caught and mapped by your `ErrorMapperService`.

**Task 1.2: Update `DefaultMessageRepository.kt` for Microsoft Provider**

* **Goal:** Similar to `DefaultFolderRepository`, modify
  `DefaultMessageRepository.launchMessageFetchJob` (and similar methods) to call `mailApiService`
  methods directly for the "MS" provider without explicit token passing.
* **File to Modify:** `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
* **Detailed Steps:**
    1. Apply the same logic changes as in Task 1.1 to
       `DefaultMessageRepository.launchMessageFetchJob`:
        * Remove explicit token fetching via `TokenProvider` for "MS" and "GOOGLE" paths when
          calling `mailApiService.getMessagesForFolder(...)`.
        * Call `mailApiService.getMessagesForFolder(folder.id, ...)` directly.
        * Re-evaluate the need for injecting `tokenProviders` map.

**Task 1.3: Evaluate and Deprecate `TokenProvider.kt` (Interface) and Implementations**

* **Goal:** Determine if the `TokenProvider` interface and its concrete implementations (
  `MicrosoftTokenProvider.kt`, and any Google equivalent if one was made for manual token passing)
  are still necessary.
* **Action:**
    * If the *only* purpose of `TokenProvider` was to supply tokens to the repositories for passing
      to `MailApiService` methods, and now `MailApiService` methods don't take tokens, then
      `TokenProvider` and its implementations become obsolete *for this specific use case*.
    * The actual token acquisition logic (calling `MicrosoftAuthManager` or `GoogleAuthManager`/
      `AppAuthHelperService`) is now encapsulated within `MicrosoftKtorTokenProvider` and
      `GoogleKtorTokenProvider` respectively, which are used by Ktor.
    * **Decision:**
        * If `TokenProvider` is not used anywhere else, you can proceed to remove it and its
          implementations from the DI graph and the codebase.
        * If it *is* used for other purposes (e.g., directly accessing a token for a non-Ktor
          related reason), then keep it but ensure its role is clearly defined.

---
### **Phase 2: UI/UX Integration for Google AppAuth Flow**
---
**Context:** The UI layer (`MainActivity`, `MainViewModel`) needs to correctly initiate the Google
AppAuth flow (which involves launching an `Intent` for a Custom Tab) and handle the redirect result.
The existing `googleConsentLauncher` in `MainActivity` is for an older `IntentSender` based flow and
needs to be adapted or replaced.

**Task 2.1: Update `MainViewModel.kt` and `MainActivity.kt` for Google AppAuth Intent Handling**

* **Goal:** Ensure the UI can correctly launch the AppAuth `Intent` provided by
  `DefaultAccountRepository` (for Google sign-in) and pass the redirect result back to the
  repository for finalization.
* **Files to Modify:**
    * `app/src/main/java/net/melisma/mail/MainViewModel.kt`
    * `app/src/main/java/net/melisma/mail/MainActivity.kt`

* **Detailed Steps (Referencing the detailed `GOOGLE.MD - Corrective Action Plan`, Phase 3, Task 3.1
  for code examples):**

    1. **`MainViewModel.kt`:**
        * Ensure it exposes the
          `defaultAccountRepository.appAuthAuthorizationIntent: Flow<Intent?>`. (Your
          `DefaultAccountRepository.kt` already has
          `val appAuthAuthorizationIntent: Flow<Intent?>`).
        * Ensure it has a method like `handleGoogleAppAuthRedirect(intentData: Intent)` that calls
          `defaultAccountRepository.finalizeGoogleAccountSetupWithAppAuth(intentData)`. (Your
          `DefaultAccountRepository.kt` also has this method).
        * Remove any code related to the old `GoogleAccountCapability` and
          `googleConsentIntentSender` if still present. (Your current `MainViewModel.kt` *still has*
          `googleConsentIntentSender` and `finalizeGoogleScopeConsent` logic for the old
          `GoogleAccountCapability`. **These must be removed.**)

    2. **`MainActivity.kt`:**
        * Replace the existing `googleConsentLauncher` (which is for `IntentSenderRequest`) with a
          new `ActivityResultLauncher<Intent>` specifically for AppAuth. Let's call it
          `googleAppAuthLauncher`.
            ```kotlin
            // In MainActivity.kt
            private lateinit var googleAppAuthLauncher: ActivityResultLauncher<Intent>

            // Inside onCreate:
            googleAppAuthLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                result.data?.let { intent ->
                    Log.d("MainActivity", "Google AppAuthLauncher received result. Passing to ViewModel.")
                    viewModel.handleGoogleAppAuthRedirect(intent) // Call the new handler in ViewModel
                } ?: run {
                    Log.w("MainActivity", "Google AppAuthLauncher result.data is null. User might have cancelled AppAuth flow.")
                    Toast.makeText(this, "Google Sign-In process was not completed.", Toast.LENGTH_SHORT).show()
                }
            }
            ```
        * In `setContent`, observe `viewModel.appAuthAuthorizationIntent`. When an `Intent` is
          emitted, launch it using `googleAppAuthLauncher.launch(intent)`.
            ```kotlin
            // In MainActivity.kt, inside setContent's LaunchedEffect (or similar)
            LaunchedEffect(key1 = viewModel) { // Key on viewModel
                viewModel.appAuthAuthorizationIntent // Observe the correct flow from MainViewModel
                    .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                    .onEach { intent ->
                        intent?.let { appAuthIntent ->
                            Log.d("MainActivity", "Received Google AppAuth intent from ViewModel. Launching...")
                            try {
                                googleAppAuthLauncher.launch(appAuthIntent)
                                // viewModel.clearAppAuthIntent() // Optional: Add method in VM/Repo to consume the event
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error launching Google AppAuth intent", e)
                                Toast.makeText(this@MainActivity, "Could not start Google Sign-In: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .launchIn(lifecycleScope)
            }
            ```
    3. **`SettingsScreen.kt`:** The `onClick = { viewModel.addGoogleAccount(activity) }` should
       correctly trigger the flow. No changes likely needed here if `MainViewModel.addGoogleAccount`
       properly calls the repository.

---
### **Phase 3: Verification, Full Functionality, and Testing**
---
**Context:** With the core plumbing for tokenless API calls and Google AppAuth UI flow in place, the
focus shifts to ensuring everything works together, implementing remaining mail features for Google,
and thorough testing.

**Task 3.1: Implement Remaining `MailApiService` Methods in `GmailApiHelper.kt`**

* **Goal:** Ensure all methods defined in the `MailApiService` interface (e.g., `getMessageDetails`,
  `sendMessage`, `deleteMessage`, `markAsRead`, etc.) are fully implemented in `GmailApiHelper.kt`
  using its authenticated Ktor client. Your current `GmailApiHelper.kt` has implementations for
  `getMailFolders`, `getMessagesForFolder` (metadata only), `markMessageRead`, `deleteMessage`, and
  `moveMessage`. Expand this.
* **File to Modify:** `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
* **Detailed Steps:**
    1. Review any methods in `MailApiService.kt` not yet fully implemented or missing in
       `GmailApiHelper.kt`.
    2. For each, identify the corresponding Gmail API endpoint(s).
    3. Implement the Ktor calls. Ktor `Auth` plugin will handle tokens.
    4. Map request/response DTOs.
    5. Use `GoogleErrorMapper` for error handling.

**Task 3.2: Connect UI Actions for Full Mail Functionality (Google & Microsoft)**

* **Goal:** Wire up UI elements for all mail actions (view full content, compose, send, reply,
  delete, mark read, move) for both Google and Microsoft accounts.
* **Files to Modify:** Relevant Composable screens, ViewModels, and Repository methods if new
  actions are needed.
* **Steps:** Ensure ViewModel methods call the appropriate repository methods, which then delegate
  to the correct `MailApiService` implementation (Google or Microsoft) without passing tokens.

**Task 3.3: Comprehensive Testing (Both Providers)**

* **Goal:** Thoroughly test all aspects of both Google and Microsoft integration.
* **Detailed Steps:**
    1. **Manual E2E Testing (Critical First Step):**
        * **Google:** Add account (Credential Manager -> AppAuth Custom Tab -> Token Persistence),
          fetch folders/messages, test token refresh (observe logs or by revoking permissions), sign
          out (verify token clearance).
        * **Microsoft:** Add account (MSAL interactive flow), fetch folders/messages, test token
          refresh, sign out.
        * Test UI interactions for all mail actions for both account types.
        * Test error conditions (no network, cancellations, API errors).
    2. **Unit Tests:**
        * Ensure `DefaultFolderRepository` and `DefaultMessageRepository` are tested for how they
          call the token-less `MailApiService` for both providers.
        * Verify tests for `GoogleKtorTokenProvider` and `MicrosoftKtorTokenProvider`.
        * Ensure `MainViewModel` tests cover the new AppAuth intent launching logic.
    3. **Instrumented/E2E Tests:**
        * Automate core scenarios: account addition (can be very tricky for UI Automator with
          external browser tabs), data fetching, critical mail actions.

## IV. Definition of "Done" for This Integration Phase

* `DefaultFolderRepository` and `DefaultMessageRepository` correctly call the token-less
  `MailApiService` methods for both Google and Microsoft providers.
* The `TokenProvider` interface and its direct implementations are deprecated/removed if no longer
  used by repositories for API calls.
* UI flow for Google AppAuth account addition (`MainActivity` launching AppAuth `Intent`,
  `MainViewModel` handling the redirect) is fully functional.
* Core mail viewing and actions (fetch folders, fetch messages, mark read, delete) are functional
  for both Google and Microsoft accounts using the new architecture.
* Comprehensive manual testing of both providers is complete and successful.
* Key unit tests for repository and ViewModel changes are implemented.

This plan now leverages your confirmation that both `GmailApiHelper` and `GraphApiHelper` are
already modernized. The focus shifts to ensuring the repositories use them correctly and the UI flow
for Google's AppAuth is properly wired.
