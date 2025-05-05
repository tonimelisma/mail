# Plan: Implementing Gmail Backend Support

This plan details adding Gmail support alongside the existing Microsoft backend, focusing on consistency, modularity, and testability.

## Phase 0: Setup & Configuration

1.  **Google Cloud Console Setup:**
    * Create/use a Google Cloud project.
    * Enable **Gmail API** and **Google People API**.
    * Create **OAuth 2.0 Client ID** for **Android**:
        * Provide package name and SHA-1 fingerprint(s).
        * Download the resulting `google-services.json` file.
    * Configure the **OAuth consent screen** (app name, logo, scopes).
        * **Required Scopes:** Start with `https://www.googleapis.com/auth/userinfo.email`, `https://www.googleapis.com/auth/userinfo.profile`, `openid` for sign-in, and add `https://www.googleapis.com/auth/gmail.readonly` for reading mail. Add more (like `gmail.modify`, `gmail.labels`) as needed for future features.

2.  **Project Setup:**
    * Place `google-services.json` into the `:app` module directory (`app/`).
    * Add/verify the Google Services Gradle plugin (`com.google.gms.google-services`) in project-level and app-level build files.
    * **Choose Networking Library:** Decide between **Retrofit** or **Ktor**. Add its dependencies, along with a JSON converter (e.g., `kotlinx.serialization`, `moshi`, `gson`).
    * Add Google library dependencies:
        * `com.google.android.gms:play-services-auth:<version>` (Google Sign-In)
        * `com.google.api-client:google-api-client-android:<version>` (Optional, base client)
        * `com.google.apis:google-api-services-gmail:v1-rev<...>-<version>` (Generated Gmail library - useful DTOs, or define your own with Retrofit/Ktor)
        * `androidx.activity:activity-ktx:<version>` (For `ActivityResultLauncher`)

3.  **Create New Modules:**
    * `backend-google`: New Android Library module for Google-specific implementations (AuthManager, ApiHelper, TokenProvider). Depends on `:core-data`.
    * `data`: New Kotlin/Java Library module. This module will contain the *default* implementations of the repository interfaces (`AccountRepository`, `FolderRepository`, `MessageRepository`). It will depend on `:core-data`, `:backend-microsoft`, and `:backend-google`.

4.  **Update Dependencies:**
    * `:app` module should depend on `:data`.
    * Remove direct dependency from `:app` to `:backend-microsoft` if it exists (DI will handle providing implementations via `:data`).

## Phase 1: Google Authentication & Core Data Integration

1.  **Step 1.1: Google Auth Manager (`:backend-google`)**
    * Create `GoogleAuthManager` in `:backend-google:auth`.
    * Inject `@ApplicationContext`.
    * **Internal Logic:** Use `GoogleSignIn.getClient()` configured with necessary scopes (email, profile, *and initial Gmail scopes*).
    * **Public API:**
        * `signIn(activity: Activity, launcher: ActivityResultLauncher<Intent>)`: Launches the Google Sign-In intent via the launcher.
        * `handleSignInResult(data: Intent?, onSuccess: (Account) -> Unit, onError: (Exception) -> Unit)`: Parses the result, fetches necessary profile info, maps `GoogleSignInAccount` to your generic `Account` (with `providerType = "GOOGLE"`), and calls callbacks.
        * `signOut(account: Account, onComplete: () -> Unit)`: Signs out the specific Google account using `GoogleSignInClient.signOut()`.
        * `getSignedInAccounts(): List<Account>`: Returns currently signed-in Google accounts mapped to your `Account` model. (Needs mechanism to store/retrieve signed-in state reliably).
        * `requestScopesIfNeeded(activity: Activity, account: Account, scopes: List<String>, ...) `: Handles incremental authorization if needed later.
    * **Testable:** Unit test `GoogleAuthManager`. Mock `GoogleSignInClient` interactions. Add a temporary debug button in `:app` to trigger `signIn` and `signOut` via a temporary instance or injected dependency. Log success/failure and the resulting `Account` object.

2.  **Step 1.2: Default Account Repository (`:data`)**
    * Create `DefaultAccountRepository` implementing `AccountRepository` from `:core-data`.
    * Inject `MicrosoftAuthManager` (from `:backend-microsoft`) and `GoogleAuthManager` (from `:backend-google`).
    * Inject `ErrorMapper`.
    * Inject `@ApplicationScope CoroutineScope`.
    * **`accounts` Flow:** Combine results from `microsoftAuthManager.accounts` (via its listener or state) and `googleAuthManager.getSignedInAccounts()` into a single `StateFlow<List<Account>>`.
    * **`authState` Flow:** Determine overall state. Could be `Initializing` until both managers report status, `Initialized` if at least one is ready, `Error` if a critical one fails. *Decision:* Keep it simple: `Initialized` means the repository is ready to be called, even if managers are still initializing internally or have no accounts. Propagate critical init errors.
    * **`addAccount(activity, scopes)`:** This needs a way to know which provider.
        * *Modify UI:* Add distinct "Add Microsoft Account" and "Add Google Account" buttons in the Settings screen.
        * *Modify Method:* Pass a `providerType: String` hint to `addAccount`, or create `addMicrosoftAccount`/`addGoogleAccount`. Let's assume the UI triggers specific actions. The repository calls the appropriate manager (`microsoftAuthManager.addAccount` or `googleAuthManager.signIn`).
    * **`removeAccount(account)`:** Check `account.providerType` and delegate to `microsoftAuthManager.removeAccount` or `googleAuthManager.signOut`.
    * **Other Flows (`isLoadingAccountAction`, `accountActionMessage`):** Manage these based on actions delegated to the auth managers.
    * **DI Change:**
        * In `:data`'s Hilt module (e.g., `DataModule.kt`), bind `AccountRepository` to `DefaultAccountRepository`.
        * Ensure `MicrosoftAuthManager` and `GoogleAuthManager` are provided by their respective backend modules (`BackendMicrosoftModule`, `BackendGoogleModule`) and available for injection into `DefaultAccountRepository`.
    * **Testable:** Run the app. Navigate to Settings -> Manage Accounts. Verify both "Add Microsoft" and "Add Google" buttons work. Verify accounts of both types appear in the UI (drawer, settings) after adding. Verify removing works for both. Check `MainViewModel` correctly receives the combined account list.

3.  **Step 1.3: Google Token Provider (`:backend-google`)**
    * Create `GoogleTokenProvider` implementing `TokenProvider` from `:core-data`.
    * Inject `@ApplicationContext`.
    * **`getAccessToken(account, scopes, activity)`:**
        * Verify `account.providerType == "GOOGLE"`.
        * Use `GoogleSignIn.getLastSignedInAccount` or find the specific `GoogleSignInAccount`.
        * **Crucially:** Use `GoogleAuthUtil.getToken` or potentially `account.serverAuthCode` (if using offline access flow) to get the OAuth **access token** for the requested *Gmail API scopes*. This often requires background execution.
        * Handle `GoogleAuthException` (especially `UserRecoverableAuthException` needing `activity.startActivityForResult`) and `IOException`.
        * Wrap results in `Result<String>`.
    * **DI Change:** In `BackendGoogleModule`, provide `GoogleTokenProvider`.
    * **Testable:** Unit test `GoogleTokenProvider`. Mock `GoogleSignIn` and `GoogleAuthUtil` calls. Verify correct token requests and error handling.

## Phase 2: Gmail API Integration & Networking

4.  **Step 2.1: Refactor Networking & Create API Helpers**
    * **Refactor `GraphApiHelper` (`:backend-microsoft`):**
        * Modify it to use the chosen networking library (Retrofit/Ktor) instead of `HttpsURLConnection`.
        * Define API interfaces (e.g., `MicrosoftGraphApiService`) with Retrofit/Ktor annotations.
        * Inject the configured networking client (provided via Hilt).
        * Inject `MicrosoftTokenProvider` (or a generic `TokenProvider` map/set). The helper needs the token *before* making the call. *Correction:* An Authenticator/Interceptor in the HTTP client is better for handling tokens.
    * **Create `GmailApiHelper` (`:backend-google`):**
        * Implement using the *same* networking library (Retrofit/Ktor).
        * Define API interfaces (e.g., `GmailApiService`) for endpoints (`labels.list`, `labels.get`, `messages.list`, `messages.get`).
        * Inject the configured networking client.
    * **Configure HTTP Client (via Hilt Module, e.g., in `:data` or `:app`):**
        * Provide a singleton instance of the Retrofit/Ktor client.
        * Add an **Interceptor/Authenticator** that:
            * Intercepts outgoing requests.
            * Determines the required provider type (perhaps based on URL or a request tag). *This is tricky.* A simpler way might be for the `ApiHelper` to request the token first and pass it to the API call function, avoiding complex interceptors for now. *Decision:* Keep it simple: `ApiHelper` gets token from `TokenProvider` first, then makes the call with the token.
            * Handles adding the `Authorization: Bearer <token>` header.
            * (Optional Advanced) Handles 401 responses by attempting silent token refresh using the appropriate `TokenProvider` and retrying the request.
    * **DI Change:** Provide the networking client, `GraphApiHelper`, and `GmailApiHelper` via Hilt modules.
    * **Testable:** Unit test both `GraphApiHelper` (post-refactor) and `GmailApiHelper`. Mock API responses. Verify parsing. Test the temporary debug screen (from Step 1.3) now uses `GmailApiHelper` to fetch labels after getting a token. Ensure Microsoft functionality still works with the refactored `GraphApiHelper`.

5.  **Step 2.2: Default Folder Repository (`:data`)**
    * Create `DefaultFolderRepository` implementing `FolderRepository` from `:core-data`.
    * Inject `GraphApiHelper` and `GmailApiHelper`.
    * Inject `Map<String, @JvmSuppressWildcards TokenProvider>` using Hilt Map Multibindings (key="MS", "GOOGLE").
    * Inject `ErrorMapper`.
    * Inject `@ApplicationScope CoroutineScope` and `@Dispatcher(IO)`.
    * **`observeFoldersState()`:** Returns the combined state flow `_folderStates`.
    * **`manageObservedAccounts(accounts)`:**
        * Iterate through accounts. Cancel jobs for removed accounts.
        * For new/existing accounts, check `providerType`.
        * If "MS", launch a job using the "MS" `TokenProvider` and `GraphApiHelper.getMailFolders`.
        * If "GOOGLE", launch a job using the "GOOGLE" `TokenProvider` and `GmailApiHelper.getLabels` (and maybe `labels.get` for counts). Map labels to `MailFolder`.
        * Update the `_folderStates: MutableStateFlow<Map<String, FolderFetchState>>` accordingly (handle Loading, Success, Error states per account ID). Use the injected `ErrorMapper`.
    * **`refreshAllFolders(activity)`:** Iterate tracked accounts, find the correct `TokenProvider` and `ApiHelper` based on `providerType`, and launch refresh jobs (passing `activity` for potential interactive token needs).
    * **DI Change:**
        * In `DataModule`, bind `FolderRepository` to `DefaultFolderRepository`.
        * Use Hilt Map Multibindings in `BackendMicrosoftModule` and `BackendGoogleModule` to provide their respective `TokenProvider` implementations into the `Map<String, TokenProvider>` injected into `DefaultFolderRepository`.
        * Ensure `GraphApiHelper` and `GmailApiHelper` are provided.
    * **Testable:** Run app. Sign in with MS and Google. Open drawer. Verify folders/labels appear correctly under each account. Test pull-to-refresh in the (not yet implemented) folder list UI or log state changes. Verify loading/error states per account.

6.  **Step 2.3: Default Message Repository (`:data`)**
    * Create `DefaultMessageRepository` implementing `MessageRepository` from `:core-data`.
    * Inject dependencies similarly to `DefaultFolderRepository` (ApiHelpers, TokenProvider Map, ErrorMapper, Scope, Dispatcher).
    * **`messageDataState`:** The `StateFlow<MessageDataState>` representing the state for the *currently selected* folder.
    * **`setTargetFolder(account, folder)`:**
        * Store `currentTargetAccount` and `currentTargetFolder`. Cancel previous fetch job.
        * If `account` is null, set state to `Initial`.
        * Check `account.providerType`.
        * If "MS", launch fetch job using "MS" `TokenProvider` and `GraphApiHelper.getMessagesForFolder`.
        * If "GOOGLE", launch fetch job using "GOOGLE" `TokenProvider` and `GmailApiHelper.getMessagesList/getMessage`. Map results to `List<Message>`.
        * Update `_messageDataState` (Loading, Success, Error). Use `ErrorMapper`.
    * **`refreshMessages(activity)`:** Check `currentTargetAccount.providerType`, get correct `TokenProvider` and `ApiHelper`, pass `activity`, and launch refresh job, updating `_messageDataState`.
    * **DI Change:** In `DataModule`, bind `MessageRepository` to `DefaultMessageRepository`. Ensure dependencies (TokenProvider Map, ApiHelpers) are provided.
    * **Testable:** Run app. Sign in with MS and Google. Select MS folder -> verify messages. Select Google label -> verify messages. Test refresh for both. Verify loading/error UI states driven by `messageDataState`.

## Phase 3: Refinement & Cleanup

7.  **Step 3.1: Error Handling & Mapping**
    * Enhance `ErrorMapper` (likely move to `:data` or `:core-common` if it needs broader scope) to handle Google-specific errors (`GoogleAuthException`, Gmail API errors) alongside MSAL/Graph errors. Ensure it's used consistently in default repositories and auth managers.
    * **Testable:** Test error scenarios (network off, invalid credentials/token revoked via provider settings) for both account types. Verify appropriate error messages in the UI.

8.  **Step 3.2: UI Consistency & Polish**
    * Review `MainActivity`, `MainViewModel`, `MailDrawerContent`, `SettingsScreen`.
    * Ensure clear visual separation or indication of account types if desired.
    * Confirm all interactions (selecting folders, viewing messages, adding/removing accounts) work smoothly regardless of provider type.
    * Address any UI adjustments needed for Gmail's label concept vs. Microsoft's folders if behaviors differ significantly (e.g., messages having multiple labels).

9.  **Step 3.3: Code Cleanup & Final Testing**
    * Remove temporary debug code, buttons, logs.
    * Refactor common mapping logic (e.g., ProviderAccount -> Core `Account`) into helper functions/extensions if duplicated.
    * Perform end-to-end testing covering all core features for both Microsoft and Google accounts.
