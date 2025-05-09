# **Refactoring and Google Backend Integration Plan (Updated May 8, 2025)**

This plan details the steps to refactor the existing codebase for better modularity and networking, followed by the implementation of Google account support using Gmail APIs.

**Assumptions:**

* Ktor with OkHttp engine will be used for networking.
* kotlinx.serialization will be used for JSON parsing.
* A :data module houses default repository implementations managing multiple providers.
* A :core-common module holds shared utilities like ErrorMapperService.
* No server-side backend exists; all operations are within the Android app.
* Offline access for Google accounts is not required initially.
* User has access to the Google Cloud Console and the Android project's signing keys (for SHA-1).

## **Completed Steps**

### **Phase 1: Core Refactoring**

* **Step 1.1: Centralize Error Mapping:**
    * Moved `ErrorMapperService` interface to `:core-common`.
    * `MicrosoftErrorMapper` in `:backend-microsoft` implements this interface.
* **Step 1.2: Refactor Networking to Ktor (backend-microsoft):**
    * Replaced `HttpsURLConnection`-based networking in `GraphApiHelper` with Ktor.
    * Ktor `HttpClient` and `Json` serializer provided via Hilt in `:backend-microsoft`.
* **Step 1.3: Refactor MicrosoftTokenProvider:**
    * `MicrosoftAuthManager` now exposes authentication results as Kotlin Flows.
    * `MicrosoftTokenProvider` consumes these Flows.
* **Step 1.4: Implement :data Module and Default Repositories:**
    * Centralized repository implementations (`DefaultAccountRepository`, `DefaultFolderRepository`, `DefaultMessageRepository`) in the `:data` module.
    * `:data` module binds these implementations to interfaces from `:core-data` via Hilt.

### **Phase 2: Google Backend Implementation (Foundational Components)**

* **Step 2.2: Create :backend-google Module:**
    * Dedicated module `:backend-google` created and configured with necessary dependencies (Google Sign-In, Ktor, Hilt).
* **Step 2.3: Implement Google Authentication (:backend-google):**
    * `GoogleAuthManager.kt` implemented with Google Sign-In/Sign-Out logic using `GoogleSignInClient`.
    * Provided via Hilt using `@Inject constructor()`.
* **Step 2.4: Implement Google Token Provider (:backend-google):**
    * `GoogleTokenProvider.kt` implemented, using `GoogleAuthUtil.getToken` for OAuth2 access tokens.
    * Provided via Hilt and qualified with `@TokenProviderType("GOOGLE")`.
* **Step 2.5: Implement Gmail API Helper (:backend-google):**
    * `GmailApiHelper.kt` created for interacting with Gmail API endpoints using Ktor.
    * `GmailModels.kt` created for Gmail API response serialization.
    * Provided via Hilt, injecting a Google-specific Ktor `HttpClient`.

## **Next Steps Required**

### **Step 2.1: Finalize Google Cloud & Project Setup**

* **Goal:** Complete client-side Google services configuration and ensure Google Cloud Console is correctly set up.
* **Actions:**
    1.  **Google Cloud Console Verification (External):**
        * Ensure your project in the [Google Cloud Console](https://console.cloud.google.com/) is active.
        * Verify that the **Gmail API** and **Google People API** are enabled under "APIs & Services" -> "Library".
        * Under "APIs & Services" -> "Credentials", confirm an **OAuth 2.0 Client ID** of type "Android" exists.
            * It must be configured with your app's package name (`net.melisma.mail`) and the correct **SHA-1 signing certificate fingerprint** for your debug and release builds.
        * Under "APIs & Services" -> "OAuth consent screen":
            * Fill in all required information (App name, User support email, Developer contact info).
            * Ensure the following scopes are added and saved:
                * `.../auth/userinfo.email` (View your email address)
                * `.../auth/userinfo.profile` (See your personal info)
                * `openid` (Associate you with your personal info on Google)
                * `https://www.googleapis.com/auth/gmail.readonly` (View your email messages and settings)
                * *(Consider adding `.../auth/gmail.modify`, `.../auth/gmail.labels`, `.../auth/gmail.send` later for more features)*.
    2.  **Add `google-services.json` to Project:**
        * If not already done after configuring the OAuth client ID, download the `google-services.json` file from the Google Cloud Console (Credentials page).
        * **Crucial:** Copy this `google-services.json` file into the **`app/`** directory of your Android Studio project. (Currently confirmed as MISSING).
    3.  **Activate Google Services Gradle Plugin:**
        * In your **`app/build.gradle.kts`** file, uncomment or add the Google Services plugin at the top:
            ```kotlin
            plugins {
                // ... other plugins
                id("com.google.gms.google-services") // Ensure this line is active
            }
            ```
        * Ensure the corresponding plugin classpath is in your project-level `build.gradle.kts` (usually handled by `alias(libs.plugins.google.services)` if using version catalogs, or explicitly `classpath 'com.google.gms:google-services:...'`).
* **Verification:**
    * After adding `google-services.json` and activating the plugin, sync Gradle. The project should build without errors related to Google Services.
    * Initial Google Sign-In attempts (once integrated into repositories) should not fail due to misconfiguration.

### **Step 2.6: Integrate Google into Default Repositories (:data)**

* **Goal:** Modify the repository implementations in the `:data` module to fully support both Microsoft and Google providers, and enhance error mapping.
* **Actions:**
    1.  **Enable `:backend-google` Dependency:**
        * In **`data/build.gradle.kts`**, uncomment the dependency:
            ```kotlin
            dependencies {
                // ... other dependencies
                implementation(project(":backend-google")) // Uncomment this line
            }
            ```
    2.  **Update `DefaultAccountRepository.kt`:**
        * Inject `GoogleAuthManager`.
        * Modify `addAccount` and `removeAccount`: Fully implement the `"GOOGLE"` provider type branches to call the appropriate methods on `GoogleAuthManager` (e.g., `googleAuthManager.signIn(...)`, `googleAuthManager.signOut(...)`, and use `googleAuthManager.handleSignInResult(...)`).
        * Update `accounts` StateFlow: Implement logic to observe and combine account information from both `MicrosoftAuthManager` and `GoogleAuthManager` (e.g., using `GoogleAuthManager.getLastSignedInAccount()` and results from `handleSignInResult`). This will require careful state management to present a unified list of accounts.
    3.  **Update `DefaultFolderRepository.kt` and `DefaultMessageRepository.kt`:**
        * **Dependency Injection:**
            * Modify constructors to inject a map of `TokenProvider`s, keyed by provider type (e.g., `"MS"`, `"GOOGLE"`): `private val tokenProviders: Map<String, @JvmSuppressWildcards TokenProvider>`. Hilt's multibindings with `@TokenProviderType` qualifier can achieve this.
            * Similarly, inject a map of API helpers (e.g., `Map<String, MailApiHelper>`). This will require creating a common interface (e.g., `MailApiHelper`) that `GraphApiHelper` and `GmailApiHelper` implement, and then providing them with type qualifiers in their respective backend Hilt modules.
        * **Logic Updates:**
            * In methods like `manageObservedAccounts`, `refreshAllFolders` (for `DefaultFolderRepository`), `setTargetFolder`, and `refreshMessages` (for `DefaultMessageRepository`), retrieve the correct `TokenProvider` and `ApiHelper` from the injected maps based on `account.providerType`.
            * Use the selected `GmailApiHelper` for Google accounts to fetch labels (as folders) and messages.
            * Remove hardcoded Microsoft-only logic (e.g., `if (account.providerType == "MS")` checks that currently exclude other types).
    4.  **Enhance Error Handling for Google Exceptions:**
        * **Goal:** Ensure Google-specific exceptions are mapped to user-friendly messages.
        * **Option 1 (Recommended - Modify `MicrosoftErrorMapper`):**
            * Rename `MicrosoftErrorMapper.kt` to a more generic name like `DefaultErrorMapper.kt` (still in `:backend-microsoft` or move to `:core-common` if it becomes truly generic).
            * Update its `mapAuthExceptionToUserMessage` and `mapNetworkOrApiException` methods to include `when` branches or `is` checks for Google-specific exceptions like `com.google.android.gms.common.api.ApiException` (for sign-in issues) and `com.google.android.gms.auth.GoogleAuthException` (for token issues from `GoogleAuthUtil`).
            * Map common Google error codes/scenarios to appropriate user messages.
        * **Option 2 (Alternative - Separate `GoogleErrorMapper`):**
            * Create `GoogleErrorMapper.kt` in `:backend-google` implementing `ErrorMapperService`.
            * Modify Hilt bindings: Instead of `BackendMicrosoftModule` directly binding `MicrosoftErrorMapper` to `ErrorMapperService`, you might need a new module in `:data` or `:app` that provides `ErrorMapperService` by deciding which concrete mapper to use, or inject `Map<String, ErrorMapperService>` into repositories. This is more complex.
* **Verification:**
    * After these changes, the app should allow adding and removing both Microsoft and Google accounts.
    * Selecting a Google account should fetch and display Gmail labels (as folders) and messages using `GmailApiHelper`.
    * Errors encountered during Google authentication or Gmail API calls should be mapped to user-friendly messages.

### **Step 2.7: UI/UX Adjustments & Final Testing**

* **Goal:** Ensure a polished and functional user experience supporting both account providers.
* **Actions:**
    1.  **UI Updates:**
        * Modify `SettingsScreen` or equivalent: Provide distinct "Add Microsoft Account" and "Add Google Account" options/buttons that correctly pass the `providerType` to `DefaultAccountRepository.addAccount(...)`.
        * Modify `MailDrawerContent`: Ensure accounts are grouped or visually distinct if desired. Handle the display of Gmail "Labels" appropriately alongside MS "Folders" (Gmail labels can be nested and have different semantics).
        * Review `MessageListContent` and `MessageListItem`: Ensure data from `Message` objects (mapped from MS Graph API or Gmail API) displays correctly and consistently.
    2.  **Thorough Testing:**
        * Perform end-to-end testing covering all implemented features (sign-in/out for both providers, folder/label view, message list view, refresh, error handling) for *both* Microsoft and Google accounts interactively.
        * Test adding multiple accounts of different types.
        * Test scenarios like token expiry, network errors, permission denials for both providers.
        * Verify UI consistency when switching between MS and Google accounts/folders.
* **Verification:** The app provides a stable, intuitive, and consistent experience for managing and viewing mail from both Microsoft and Google accounts.




