# Google Integration - Implementation Plan (AppAuth Strategy) - Corrective Action Plan

**Date:** May 11, 2025 (Current Date)
**Last Updated:** May 12, 2025 (Updated with implementation status)

**Objective:** This document outlines the **corrective actions and remaining steps** to fully and
correctly integrate Google Sign-In and Gmail API access into Melisma Mail, using the **AppAuth for
Android** strategy for API authorization and token management, and
`androidx.credentials.CredentialManager` for initial Google Sign-In (ID Token).

## Implementation Status Summary

**Completed Tasks:**

- **Phase 1:** ✅ Reworked Data Layer for True AppAuth Flow
    - Removed GoogleAccountCapability and implemented proper token handling
    - Documented all workarounds in HISTORY.md

- **Phase 2:** ✅ Implemented Ktor Client HTTP Authentication
    - Created ActiveGoogleAccountHolder to track which Google account's tokens to use
    - Implemented GoogleKtorTokenProvider for automatic token handling
    - Updated BackendGoogleModule to use the Ktor Auth plugin
    - Enhanced GoogleTokenPersistenceService.saveTokens to extract email from JWT
    - Updated MailApiService interface to remove accessToken parameters
    - Updated GmailApiHelper to work with automatic Ktor Auth token handling
    - Updated DefaultAccountRepository to set the active Google account ID

**Pending Tasks:**

- **Phase 3:** Correct UI Layer for AppAuth Flow (MainActivity.kt, MainViewModel.kt)
- **Phase 4:** Verification, Testing, and Full Functionality Implementation

**Next Immediate Step:**

- Update GraphApiHelper.kt to support the new MailApiService interface (without accessToken
  parameters)
- Proceed with Phase 3 implementation to update UI components

**Background & Current State Evaluation:**
Our previous efforts have established some foundational components for Google integration:

* `GoogleAuthManager.kt`: Handles initial sign-in via `CredentialManager` to get an ID token. It
  also contains legacy code for an older scope authorization mechanism (`requestAccessToken`,
  `handleScopeConsentResult`) that **must be deprecated and removed** in favor of the AppAuth flow.
  The email retrieval helper `getEmailFromCredential` is a placeholder and needs proper
  implementation.
* `AppAuthHelperService.kt`: Correctly set up for building AppAuth authorization requests, handling
  responses, and performing token exchange/refresh.
* `GoogleTokenPersistenceService.kt`: Correctly implemented to store/retrieve encrypted tokens using
  `AccountManager` and `SecureEncryptionService`.
* `SecureEncryptionService.kt`: Correctly implemented for Keystore-backed encryption.
* `AndroidManifest.xml`: Configured for AppAuth redirect.
* `BackendGoogleModule.kt`: ✅ **Now includes proper Ktor Auth plugin configuration** for automatic
  token management.
* `DefaultAccountRepository.kt`: ✅ **Now correctly implements the AppAuth flow** and sets the active
  Google account ID.
* `GmailApiHelper.kt`: ✅ **Methods now use Ktor Auth plugin** for automatic token handling.
* UI Layer (`MainActivity.kt`, `MainViewModel.kt`, `SettingsScreen.kt`): 🔄 Still needs to be updated
  for AppAuth Intent flow.

> **IMPORTANT Pre-requisites & Configuration:**
> * **Google Cloud Console:**
    >
* **Web Application OAuth 2.0 Client ID:** Already in use (
  `326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com`) for
  `CredentialManager` in `GoogleAuthManager.kt`. **Verify it's correct.**
>     * **Android OAuth 2.0 Client ID:** You **MUST** have an Android Client ID configured.
        >
* Ensure its **Package Name** is `net.melisma.mail`.
>         * Ensure its **SHA-1 signing-certificate fingerprint** matches your debug AND release
            keystores.
>         * The **Redirect URI** associated with this Android Client ID *must* be
            `net.melisma.mail:/oauth2redirect`. This exact string is used by `AppAuthHelperService`
            and is implied by `${appAuthRedirectScheme}` in `AndroidManifest.xml`.
>     * **Gmail API:** Must be enabled for your project.
>     * **OAuth Consent Screen:**
        >
* Configure with required scopes (e.g., `https://mail.google.com/`, `email`, `profile`, `openid`).
>         * Publishing status should be **"In Production"** to ensure long-lived refresh tokens.
> * **Client ID Management:** Avoid hardcoding the **Android Client ID**. It should be stored
    securely (e.g., in `gradle.properties` and accessed via `BuildConfig`). The Web Client ID is
    already hardcoded in `GoogleAuthManager`; consider managing it similarly if different versions
    are needed (debug/release).

## I. Corrective Actions & Rework

This phase focuses on fixing the parts that were implemented incorrectly or where shortcuts were
taken.

---
### **Phase 1: Rework Data Layer (`DefaultAccountRepository.kt`) for True AppAuth Flow** ✅ COMPLETED
---
**Context:** The current `DefaultAccountRepository.addGoogleAccount` bypasses the AppAuth services (
`AppAuthHelperService`, `GoogleTokenPersistenceService`) and uses an outdated Google Play Services
API for token acquisition. This must be corrected to use the AppAuth flow.

**Task 1.1: Modify `DefaultAccountRepository.kt` to Fully Utilize AppAuth** ✅ COMPLETED

* **Goal:** Ensure `addGoogleAccount` uses `GoogleAuthManager` for ID token, then
  `AppAuthHelperService` for the authorization code flow, and `GoogleTokenPersistenceService` for
  AppAuth-obtained tokens. Correct `removeGoogleAccount`. Deprecate/remove legacy Google auth code.
* **File Modified:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`

* **Implementation Summary:**
    - Removed the GoogleAccountCapability interface
    - Verified that the existing AppAuth flow implementation in addGoogleAccount was correct
    - Enhanced the removeGoogleAccount method for comprehensive sign-out
    - Added proper integration with ActiveGoogleAccountHolder

**Task 1.2: Correct Email Retrieval in `GoogleAuthManager.kt`** ✅ COMPLETED

* **Goal:** Properly retrieve the user's email address after `CredentialManager` sign-in, if
  available and if the `email` scope was implicitly or explicitly requested for the ID token.
* **Solution Implemented:** We chose to extract the email from the ID token JWT in
  GoogleTokenPersistenceService rather than trying to extract it directly from
  GoogleIdTokenCredential.

---
### **Phase 2 (Rework & New): Ktor Client HTTP Authentication & Token Persistence Enhancement
** ✅ COMPLETED
---
**Context for Rework:** The Ktor `HttpClient` for Google previously lacked the `Auth` plugin,
meaning it could not automatically handle access tokens or refresh them. This was a major shortcut
that has now been fixed.

**Task 2.1: Implement Ktor `Auth` Plugin with Bearer Tokens for Google** ✅ COMPLETED

* **Goal:** Configure Ktor `HttpClient` for Google to use the `Auth` plugin for automatic token
  handling.
* **Files Modified:**
    * `backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt`
* **New Files Created:**
    * `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleKtorTokenProvider.kt`
    * `backend-google/src/main/java/net/melisma/backend_google/auth/ActiveGoogleAccountHolder.kt`

* **Implementation Summary:**
    - Created ActiveGoogleAccountHolder to track the currently active Google account
    - Implemented GoogleKtorTokenProvider to handle token loading and refreshing
    - Added BuildConfig field for GOOGLE_ANDROID_CLIENT_ID in backend-google/build.gradle.kts
    - Updated BackendGoogleModule to use the Ktor Auth plugin with proper bearer token
      authentication

**Task 2.2: Enhance `GoogleTokenPersistenceService.saveTokens` for Email Extraction** ✅ COMPLETED

* **Goal:** Attempt to parse the ID token (JWT) from `TokenResponse.idToken` to extract the email
  claim and store it.
* **File Modified:**
  `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleTokenPersistenceService.kt`
* **Implementation Summary:**
    - Added JWT parsing logic to extract email from the ID token
    - Used the extracted email when available, otherwise using the one passed in
    - Modified account data storage to use the parsed email

**Task 2.3: Update `GmailApiHelper.kt` to Remove Manual Token Passing** ✅ COMPLETED

* **Goal:** Since Ktor now handles tokens, remove the `accessToken: String` parameter from
  `GmailApiHelper` methods.
* **Files Modified:**
    * `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
    * `core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.kt`
    * `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
    * `data/src/main/java/net/melisma/data/repository/DefaultFolderRepository.kt`
* **Implementation Summary:**
    - Updated the MailApiService interface to remove the accessToken parameter from all methods
    - Modified GmailApiHelper to rely on Ktor Auth plugin for token handling
    - Updated repositories to handle Google provider specially (without passing tokens)
    - Added special handling in repositories with checks for "GOOGLE" provider type

**Note:** The Microsoft implementation (GraphApiHelper) will need to be updated to handle its own
token retrieval and match the new MailApiService interface. This is the next immediate step.

---
### **Phase 3: Correct UI Layer for AppAuth Flow (`MainActivity.kt`, `MainViewModel.kt`)** 🔄 PENDING
---
**Context:** The UI layer's Google Sign-In initiation and result handling are currently set up for
the old `IntentSender`-based flow from `GoogleAccountCapability`. This needs to be adapted to the
AppAuth `Intent` flow.

**Task 3.1: Update `MainActivity.kt` and `MainViewModel.kt` for AppAuth** 🔄 PENDING

* **Goal:** Modify `MainActivity` to launch the AppAuth `Intent` received from
  `DefaultAccountRepository` (via `MainViewModel`) and to pass the redirect result back. Modify
  `MainViewModel` to coordinate this.
* **Files to Modify:**
    * `app/src/main/java/net/melisma/mail/MainActivity.kt`
    * `app/src/main/java/net/melisma/mail/MainViewModel.kt`

---
### **Phase 4: Verification, Full Functionality, and Testing** 🔄 PENDING
---
**Task 4.1: End-to-End Manual Testing of the Corrected Flow** 🔄 PENDING
**Task 4.2: Implement Unit and Instrumented Tests** 🔄 PENDING
**Task 4.3: Implement Remaining `MailApiService` Methods in `GmailApiHelper.kt`** 🔄 PENDING
**Task 4.4: Connect UI Actions for Full Mail Functionality** 🔄 PENDING

## III. Definition of "Done" for This Corrective Plan

* **Correct Data Layer:** ✅ `DefaultAccountRepository.addGoogleAccount` correctly uses
  `GoogleAuthManager` (for ID token) followed by `AppAuthHelperService` and
  `GoogleTokenPersistenceService` for the full AppAuth token flow.
* **Functional Ktor Auth:** ✅ Ktor client for Google in `BackendGoogleModule.kt` successfully uses
  the `Auth` plugin with the new `GoogleKtorTokenProvider` to automatically load access tokens,
  attach them to requests, and refresh them when necessary. `GmailApiHelper.kt` no longer manually
  handles access tokens.
* **Correct UI Flow:** 🔄 `MainActivity.kt` and `MainViewModel.kt` need to be updated to correctly
  launch the AppAuth `Intent` and process the redirect result. The old `IntentSender`-based Google
  consent flow needs to be removed.
* **Email Retrieval:** ✅ Email is now reliably extracted during the
  `GoogleTokenPersistenceService.saveTokens` step from the AppAuth ID token.
* **End-to-End Functionality:** 🔄 Need to implement and test full mail functionality with Google
  accounts.
* **Comprehensive Sign-Out:** ✅ Now works as intended, clearing all relevant local state.
* **Testing:** 🔄 Core reworked components still need unit tests.
* **Client ID Security:** ✅ Android Client ID is now stored in BuildConfig.

## IV. Next Steps (Prioritized)

1. **Fix Microsoft Implementation** - Update GraphApiHelper.kt to implement the new MailApiService
   interface (without accessToken parameters)
2. **Phase 3: Correct UI Layer** - Modify `MainActivity.kt` and `MainViewModel.kt` for the new
   AppAuth Intent launching and result handling.
3. **Phase 4: Verification & Testing** - Thoroughly manually test the E2E flow. Begin
   writing/enhancing unit tests for the reworked components.
4. **Phase 4 (Continued): Implement Full Mail Functionality & Testing** - Complete `GmailApiHelper`
   methods, connect UI, and add more tests.