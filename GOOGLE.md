# **Refactoring and Google Backend Integration Plan**

This plan details the steps to refactor the existing codebase for better modularity and networking,
followed by the implementation of Google account support using Gmail APIs.  
**Assumptions:**

* Ktor with OkHttp engine will be used for networking.
* kotlinx.serialization will be used for JSON parsing.
* A new :data module will be created to house default repository implementations managing multiple
  providers.
* A new :core-common module will be created for shared utilities like ErrorMapper.
* No server-side backend exists; all operations are within the Android app.
* Offline access for Google accounts is not required initially.
* User has access to the Google Cloud Console and the Android project's signing keys (for SHA-1).

## **Phase 1: Core Refactoring**

*(Steps 1.1 \- 1.4 focus on improving the existing structure before adding new features)*

### **Step 1.1: Centralize Error Mapping**

* **Goal:** Move ErrorMapper to a shared, backend-agnostic module.
* **Actions:**
    1. Create a new Gradle module named :core-common. This module will hold utilities shared across
       different layers or backends but not specific to core data models/interfaces.
    2. Configure settings.gradle.kts to include :core-common.
    3. Configure core-common/build.gradle.kts as a basic Kotlin/Android library (similar setup to :
       core-data, likely without Android-specific dependencies unless needed later). Add dependency
       on kotlin-stdlib.
    4. Move ErrorMapper.kt from net.melisma.backend\_microsoft.errors to a package within :
       core-common (e.g., net.melisma.core\_common.errors). Update the package declaration inside
       ErrorMapper.kt.
    5. Add implementation(project(":core-common")) dependency to backend-microsoft/build.gradle.kts.
    6. Update the import statements for ErrorMapper in MicrosoftAccountRepository.kt,
       MicrosoftFolderRepository.kt, and MicrosoftMessageRepository.kt (Note: These files will be
       moved to :data in Step 1.4, so ensure imports are correct there).
* **Verification:** Build the project successfully. Run the app and test Microsoft account error
  scenarios (e.g., turn off network during folder load) to ensure errors are still mapped and
  displayed correctly.

### **Step 1.2: Refactor Networking to Ktor (backend-microsoft)**

* **Goal:** Replace the HttpsURLConnection-based networking in GraphApiHelper with Ktor, aligning
  with the chosen stack.
* **Actions:**
    1. **Dependencies:** Add Ktor & Serialization dependencies to gradle/libs.versions.toml (if not
       already present from previous plans):  
       \[versions\]  
       \# ... existing versions  
       ktor \= "2.3.12" \# Use the latest stable Ktor 2.x version  
       kotlinxSerialization \= "1.6.3" \# Use latest stable serialization version

       \[libraries\]  
       \# ... existing libraries  
       ktor-client-core \= { module \= "io.ktor:ktor-client-core", version.ref \= "ktor" }  
       ktor-client-okhttp \= { module \= "io.ktor:ktor-client-okhttp", version.ref \= "ktor" }  
       ktor-client-contentnegotiation \= { module \= "io.ktor:ktor-client-content-negotiation",
       version.ref \= "ktor" }  
       ktor-serialization-kotlinx-json \= { module \= "io.ktor:ktor-serialization-kotlinx-json",
       version.ref \= "ktor" }  
       kotlinx-serialization-json \= { group \= "org.jetbrains.kotlinx", name \= "
       kotlinx-serialization-json", version.ref \= "kotlinxSerialization" }

    2. **Apply Dependencies:** Add the following to backend-microsoft/build.gradle.kts dependencies
       block:  
       implementation(libs.ktor.client.core)  
       implementation(libs.ktor.client.okhttp)  
       implementation(libs.ktor.client.contentnegotiation)  
       implementation(libs.ktor.serialization.kotlinx.json)  
       implementation(libs.kotlinx.serialization.json) // Core serialization runtime

    3. **Provide HttpClient:** Create a Hilt module in :backend-microsoft (e.g., NetworkModule.kt)
       to provide a singleton Ktor HttpClient:  
       package net.melisma.backend\_microsoft.di // Or appropriate package

       import dagger.Module  
       import dagger.Provides  
       import dagger.hilt.InstallIn  
       import dagger.hilt.components.SingletonComponent  
       import io.ktor.client.\*  
       import io.ktor.client.engine.okhttp.\*  
       import io.ktor.client.plugins.contentnegotiation.\*  
       import io.ktor.client.plugins.logging.\* // Optional: for logging requests/responses  
       import io.ktor.serialization.kotlinx.json.\*  
       import kotlinx.serialization.json.Json  
       import java.util.concurrent.TimeUnit // For OkHttp timeouts if needed  
       import javax.inject.Singleton

       @Module  
       @InstallIn(SingletonComponent::class)  
       object NetworkModule {

           @Provides  
           @Singleton  
           fun provideJson(): Json \= Json {  
               prettyPrint \= true // Good for debugging  
               isLenient \= true  
               ignoreUnknownKeys \= true // Essential for API stability  
           }

           @Provides  
           @Singleton  
           fun provideKtorHttpClient(json: Json): HttpClient {  
               return HttpClient(OkHttp) {  
                   // Engine configuration (OkHttp specific)  
                   engine {  
                       config {  
                           connectTimeout(30, TimeUnit.SECONDS)  
                           readTimeout(30, TimeUnit.SECONDS)  
                           // Configure retries, interceptors etc. if needed  
                       }  
                   }

                   // JSON Serialization  
                   install(ContentNegotiation) {  
                       json(json) // Use the provided Json instance  
                   }

                   // Optional: Logging  
                   install(Logging) {  
                       logger \= Logger.DEFAULT // Or use a custom logger  
                       level \= LogLevel.BODY // Log request/response bodies (use INFO for less detail)  
                   }

                   // Optional: Default request parameters  
                   // install(DefaultRequest) { header(HttpHeaders.ContentType, ContentType.Application.Json) }  
               }  
           }  
       }

    4. **Refactor GraphApiHelper.kt:**
        * Inject the HttpClient provided by Hilt: class GraphApiHelper @Inject constructor(private
          val httpClient: HttpClient)
        * Define Kotlin data classes marked with @Serializable within backend-microsoft (e.g., in a
          model or dto sub-package) to represent the structure of Graph API JSON responses (e.g.,
          for /me/mailFolders, /me/mailFolders/{id}/messages). Ensure property names match the JSON
          keys or use @SerialName.
        * Rewrite getMailFolders and getMessagesForFolder methods:
            * Use httpClient.get with a URL builder/string for the endpoint.
            * Add the Authorization: Bearer $accessToken header using bearerAuth(accessToken).
            * Use response.body\<YourSerializableDataClass\>() within a try-catch block to get the
              parsed response.
            * Map the received DTO data class to your :core-data model (MailFolder, Message).
            * Update error handling: Catch Ktor exceptions like ClientRequestException,
              ServerResponseException, RedirectResponseException, SerializationException, as well as
              general IOException, etc. Use the (moved) ErrorMapper to convert these into
              user-friendly messages. mapGraphExceptionToUserMessage in ErrorMapper will need
              updating to recognize Ktor exceptions.
* **Verification:** Run the app. Sign in with Microsoft. Verify folders and messages load correctly
  via Ktor (check logs for Ktor output if logging is enabled). Test network error scenarios.

### **Step 1.3: (Optional but Recommended) Refactor MicrosoftTokenProvider**

* **Goal:** Improve testability and align MicrosoftTokenProvider with modern coroutine flow
  patterns.
* **Actions:**
    1. Modify MicrosoftAuthManager (implementation assumed) to expose its asynchronous auth
       results (AcquireTokenResult) using kotlinx.coroutines.flow.callbackFlow instead of simple
       callbacks.
    2. Update MicrosoftTokenProvider.kt to collect results from these flows instead of using
       CompletableDeferred.
* **Verification:** Run the app. Verify Microsoft sign-in and subsequent authenticated actions (
  loading folders/messages which require tokens) still work seamlessly.

### **Step 1.4: Implement :data Module and Default Repositories**

* **Goal:** Centralize repository implementations to prepare for handling multiple backends (
  Microsoft and Google).
* **Actions:**
    1. Create a new Android Library module named :data.
    2. Add :data to settings.gradle.kts.
    3. Configure data/build.gradle.kts:
        * Apply necessary plugins (Android library, Kotlin, Kapt, Hilt).
        * Set up android { ... } block (namespace, compileSdk, etc., consistent with other modules).
        * Add dependencies:  
          dependencies {  
          implementation(project(":core-data"))  
          implementation(project(":core-common")) // For ErrorMapper  
          implementation(project(":backend-microsoft")) // For MS-specific components (AuthManager,
          ApiHelper, TokenProvider)

              implementation(libs.hilt.android)  
              kapt(libs.hilt.compiler)  
              implementation(libs.kotlinx.coroutines.core) // If using coroutines directly

              // Add testing dependencies as needed (junit, mockk, turbine, coroutines-test)  
          }

    4. Move the implementation files (MicrosoftAccountRepository.kt, MicrosoftFolderRepository.kt,
       MicrosoftMessageRepository.kt) from :backend-microsoft/src/main/java/.../repository to :
       data/src/main/java/net/melisma/data/repository.
    5. Update the package declarations in the moved files to net.melisma.data.repository.
    6. Rename the moved classes:
        * MicrosoftAccountRepository \-\> DefaultAccountRepository
        * MicrosoftFolderRepository \-\> DefaultFolderRepository
        * MicrosoftMessageRepository \-\> DefaultMessageRepository
    7. Update import statements within these moved files if necessary (e.g., for ErrorMapper,
       GraphApiHelper, MicrosoftTokenProvider).
    8. Create a Hilt module in :data (e.g., DataModule.kt) to bind the repository *interfaces*
       from :core-data to these *default implementations*:  
       package net.melisma.data.di // Or appropriate package

       import dagger.Binds  
       import dagger.Module  
       import dagger.hilt.InstallIn  
       import dagger.hilt.components.SingletonComponent  
       import net.melisma.core\_data.repository.AccountRepository  
       import net.melisma.core\_data.repository.FolderRepository  
       import net.melisma.core\_data.repository.MessageRepository  
       import net.melisma.data.repository.DefaultAccountRepository  
       import net.melisma.data.repository.DefaultFolderRepository  
       import net.melisma.data.repository.DefaultMessageRepository  
       import javax.inject.Singleton

       @Module  
       @InstallIn(SingletonComponent::class)  
       abstract class DataModule {

           @Binds  
           @Singleton  
           abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository

           @Binds  
           @Singleton  
           abstract fun bindFolderRepository(impl: DefaultFolderRepository): FolderRepository

           @Binds  
           @Singleton  
           abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository  
       }

    9. Update app/build.gradle.kts: Replace implementation(project(":backend-microsoft")) with
       implementation(project(":data")). The :app module should now depend on the data layer, not
       specific backends.
    10. Review backend-microsoft/build.gradle.kts: It should no longer need dependencies like Hilt
        unless it's providing components directly used *only* within that module (like the Ktor
        client if not moved, or AuthManager). The repository implementations are gone. It primarily
        provides helpers (GraphApiHelper) and auth components (MicrosoftAuthManager,
        MicrosoftTokenProvider) to be injected into the :data module.
* **Verification:** Clean and rebuild the project. Run the app. Verify all Microsoft functionality (
  sign-in, listing folders, listing messages) works exactly as before. The underlying implementation
  module (:data) has changed, but the behavior should be identical. Check that MainViewModel still
  injects the repository interfaces without issue.

## **Phase 2: Google Backend Implementation**

*(Execute these steps after Phase 1 refactoring is complete and verified)*

### **Step 2.1: Google Cloud & Project Setup**

* **Goal:** Configure Google services access for the Android app and add base dependencies.
* **Actions:**
    1. **Google Cloud Console:**
        * Navigate to your
          project: [https://console.cloud.google.com/](https://console.cloud.google.com/)
        * **Enable APIs:** Go to "APIs & Services" \-\> "Library". Search for and enable:
            * Gmail API
            * Google People API (Often used with sign-in to get profile info)
        * **Credentials:** Go to "APIs & Services" \-\> "Credentials".
            * Click "+ CREATE CREDENTIALS" \-\> "OAuth client ID".
            * Select "Application type" \-\> Android.
            * Give it a name (e.g., "Melisma Mail Android Client").
            * Enter your app's package name: net.melisma.mail.
            * Generate your SHA-1 signing certificate fingerprint:
                * In Android Studio Terminal: ./gradlew signingReport
                * Copy the SHA-1 value for your debug variant.
                * *(Important: When you create a release build, you MUST add the release
                  certificate's SHA-1 here as well)*.
            * Paste the SHA-1 fingerprint and click "CREATE".
        * **OAuth Consent Screen:** Go to "APIs & Services" \-\> "OAuth consent screen".
            * Ensure "User Type" is appropriate (likely "External" unless using Workspace).
            * Fill in required info: App name ("Melisma Mail"), User support email, App logo (
              optional), Developer contact info.
            * **Scopes:** Click "ADD OR REMOVE SCOPES". Add the following essential scopes:
                * .../auth/userinfo.email (View your email address)
                * .../auth/userinfo.profile (See your personal info, including any personal info
                  you've made publicly available)
                * openid (Associate you with your personal info on Google)
                * https://www.googleapis.com/auth/gmail.readonly (View your email messages and
                  settings) \- Crucial for reading mail.
            * *(Optional Scopes for later: Add .../auth/gmail.modify for actions like mark
              read/delete, .../auth/gmail.labels for label management, .../auth/gmail.send for
              sending mail)*.
            * Save the consent screen configuration. If "External", you might need to submit for
              verification later for non-test users, but testing should work immediately.
        * **Download JSON:** Go back to "Credentials", find your Android Client ID, and click the
          download button (looks like a down arrow) to get the google-services.json file.
    2. **Android Project Setup:**
        * Copy the downloaded google-services.json file into the app/ directory of your project.
        * **Gradle Plugin:** Ensure the Google Services Gradle plugin is set up:
            * Project build.gradle.kts: Check for alias(libs.plugins.google.services) or classpath "
              com.google.gms:google-services:...". (Add if missing).
            * App app/build.gradle.kts: Apply the plugin at the top: id("
              com.google.gms.google-services").
        * **Dependencies:** Add to gradle/libs.versions.toml:  
          \[versions\]  
          \# ...  
          playServicesAuth \= "21.1.0" \# Check for latest version  
          androidxActivityKtx \= "1.9.0" \# Check for latest version

          \[libraries\]  
          \# ...  
          google-play-services-auth \= { group \= "com.google.android.gms", name \= "
          play-services-auth", version.ref \= "playServicesAuth" }  
          androidx-activity-ktx \= { group \= "androidx.activity", name \= "activity-ktx",
          version.ref \= "androidxActivityKtx" }

* **Verification:** Sync the Gradle project. Ensure no build errors related to Google Services or
  dependencies occur.

### **Step 2.2: Create :backend-google Module**

* **Goal:** Create a dedicated module for Google-specific authentication and API interaction logic.
* **Actions:**
    1. Create a new Android Library module named :backend-google.
    2. Add :backend-google to settings.gradle.kts.
    3. Configure backend-google/build.gradle.kts:
        * Apply necessary plugins (Android library, Kotlin, Kapt, Hilt).
        * Set up android { ... } block.
        * Add dependencies:  
          dependencies {  
          implementation(project(":core-data"))  
          implementation(project(":core-common")) // For ErrorMapper

              // Google Sign-In  
              implementation(libs.google.play.services.auth)  
              implementation(libs.androidx.activity.ktx) // Needed for ActivityResultLauncher

              // Ktor Client (Inject from :backend-microsoft or a new :core-network module)  
              implementation(libs.ktor.client.core) // Needed for HttpClient type  
              // DO NOT add engine/serialization here if provided elsewhere

              // Hilt  
              implementation(libs.hilt.android)  
              kapt(libs.hilt.compiler)  
              implementation(libs.kotlinx.coroutines.core)

              // Required for Ktor JSON parsing  
              implementation(libs.ktor.serialization.kotlinx.json)  
              implementation(libs.kotlinx.serialization.json)

              // Testing  
              // ...  
          }

* **Verification:** The new module is created and the project builds successfully.

### **Step 2.3: Implement Google Authentication (:backend-google)**

* **Goal:** Implement logic for signing users in and out with their Google accounts.
* **Actions:**
    1. Create GoogleAuthManager.kt in :backend-google (e.g., net.melisma.backend\_google.auth).
        * Make it injectable (@Inject constructor(...), @Singleton).
        * Inject @ApplicationContext context: Context.
        * Inside, create a GoogleSignInClient:  
          private val gso \= GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT\_SIGN\_IN)  
          .requestEmail() // Includes email, profile, openid implicitly  
          .requestScopes(Scope("https://www.googleapis.com/auth/gmail.readonly")) // Add Gmail
          scope  
          // .requestServerAuthCode(WEB\_CLIENT\_ID) // Not needed without backend/offline  
          .build()  
          private val googleSignInClient \= GoogleSignIn.getClient(context, gso)

        * Implement signIn(activity: Activity, launcher: ActivityResultLauncher\<Intent\>): Call
          launcher.launch(googleSignInClient.signInIntent).
        * Implement handleSignInResult(data: Intent?, onSuccess: (Account) \-\> Unit, onError: (
          Exception) \-\> Unit):
            * Use GoogleSignIn.getSignedInAccountFromIntent(data) in a try-catch block for
              ApiException.
            * On success (task.getResult(ApiException::class.java)), map the GoogleSignInAccount (
              non-null fields: id, email, displayName) to your Account model (providerType \= "
              GOOGLE"). Call onSuccess.
            * On failure, call onError with the caught exception.
        * Implement signOut(account: Account, onComplete: () \-\> Unit): Call
          googleSignInClient.signOut().addOnCompleteListener { onComplete() }.
        * Implement getSignedInAccount(): Account?: Use GoogleSignIn.getLastSignedInAccount(context)
          to check for an existing session, map it to your Account model if non-null.
    2. Create a Hilt module in :backend-google (e.g., BackendGoogleModule.kt) to provide
       GoogleAuthManager as a Singleton.
* **Verification:** Modify DefaultAccountRepository's addAccount to accept a provider hint. Add a
  temporary "Add Google Account" button in SettingsScreen that calls accountRepository.addAccount(
  activity, scopes, "GOOGLE"). Verify the Google Sign-In screen appears, sign-in works, and the
  account appears in the UI (requires Step 2.6 partially). Verify sign-out removes the account.

### **Step 2.4: Implement Google Token Provider (:backend-google)**

* **Goal:** Implement the TokenProvider interface to get OAuth2 access tokens for making Gmail API
  calls.
* **Actions:**
    1. Create GoogleTokenProvider.kt implementing TokenProvider in :backend-google (e.g., datasource
       package).
    2. Make it injectable (@Inject constructor(...), @Singleton). Inject @ApplicationContext
       context: Context, @Dispatcher(MailDispatchers.IO) private val ioDispatcher:
       CoroutineDispatcher.
    3. Implement getAccessToken(account: Account, scopes: List\<String\>, activity: Activity?):
       Result\<String\>:
        * Run the core logic within withContext(ioDispatcher).
        * Check account.providerType \== "GOOGLE". Return Result.failure if not.
        * Get the GoogleSignInAccount: val googleAccount \= GoogleSignIn.getLastSignedInAccount(
          context) (ensure it matches account.id if multiple Google accounts were supported). Return
          failure if null or doesn't match.
        * Check if required scopes are already granted using GoogleSignIn.hasPermissions(
          googleAccount, \*scopes.map { Scope(it) }.toTypedArray()).
        * **If permissions granted:**
            * Use GoogleAuthUtil.getToken(context, googleAccount.account\!\!, "oauth2:$
              {scopes.joinToString(" ")}"). This must NOT be called on the main thread.
            * Wrap the result in Result.success.
            * Catch GoogleAuthException (map using ErrorMapper), IOException. Return Result.failure.
        * **If permissions NOT granted:**
            * Return Result.failure indicating missing permissions. The UI/ViewModel layer would
              need to trigger a re-authentication via GoogleAuthManager requesting the *needed*
              scopes.
    4. Add GoogleTokenProvider to BackendGoogleModule using @Provides.
* **Verification:** Unit test GoogleTokenProvider mocking GoogleSignIn and GoogleAuthUtil. Add
  temporary code to call getAccessToken after sign-in and log the token or error.

### **Step 2.5: Implement Gmail API Helper (:backend-google)**

* **Goal:** Create a helper class to interact with Gmail API endpoints using Ktor.
* **Actions:**
    1. Create GmailApiHelper.kt in :backend-google.
    2. Make it injectable (@Inject constructor(...), @Singleton). Inject the Ktor HttpClient (
       provided from :backend-microsoft or a shared network module).
    3. Define @Serializable data classes within :backend-google for Gmail API v1 responses (e.g.,
       GmailLabelList, GmailLabel, GmailMessageList, GmailMessage, MessagePartHeader, etc.). Refer
       to the [Gmail API documentation](https://developers.google.com/gmail/api/reference/rest).
    4. Implement API call methods:
        * suspend fun getLabels(accessToken: String): Result\<List\<MailFolder\>\>: Calls
          GET https://gmail.googleapis.com/gmail/v1/users/me/labels. Parses GmailLabelList, maps
          labels array to List\<MailFolder\>.
        * suspend fun getMessagesForList(accessToken: String, labelIds: List\<String\>, maxResults:
          Int): Result\<List\<Message\>\>:
            * Call GET .../messages with labelIds, maxResults.
            * For each message ID in the response, call GET
              .../messages/{id}?format=metadata\&metadataHeaders=Subject\&metadataHeaders=From\&metadataHeaders=Date\&metadataHeaders=Snippet.
              *(Limit concurrent requests or use batching later)*.
            * Parse metadata, map to Message (extract sender, date, subject, use snippet, check for
              UNREAD label). Return List\<Message\>.
    5. Use Ktor client (httpClient.get), add bearerAuth(accessToken), parse with
       response.body\<DataClass\>(), handle exceptions using ErrorMapper.
    6. Add GmailApiHelper to BackendGoogleModule using @Provides.
* **Verification:** Unit test GmailApiHelper mocking Ktor responses. Add temporary code: After
  getting a Google token, call getLabels and log the results.

### **Step 2.6: Integrate Google into Default Repositories (:data)**

* **Goal:** Modify the repository implementations in the :data module to support both Microsoft and
  Google providers.
* **Actions:**
    1. **Dependency:** Add implementation(project(":backend-google")) to data/build.gradle.kts.
    2. **Modify DefaultAccountRepository.kt (:data):**
        * Inject GoogleAuthManager alongside MicrosoftAuthManager.
        * Update addAccount: Accept a providerType: String hint. Based on the hint, call either
          microsoftAuthManager.addAccount(...) or googleAuthManager.signIn(...). Handle results
          appropriately. *(Need to adjust the interface AccountRepository or add a new method)*.
          Let's adjust the interface: addAccount(activity: Activity, scopes: List\<String\>,
          providerType: String)
        * Update removeAccount: Check account.providerType. If "MS", call
          microsoftAuthManager.removeAccount; if "GOOGLE", call googleAuthManager.signOut.
        * Update account state (accounts flow): Combine results from microsoftAuthManager.accounts
          and googleAuthManager.getSignedInAccount(). This needs careful state management,
          potentially listening to both managers.
    3. **Modify DefaultFolderRepository.kt (:data):**
        * Inject GoogleTokenProvider and GmailApiHelper. *(Consider using Hilt Map Multibindings for
          TokenProvider and API helpers to simplify injection)*.
            * Example: Inject Map\<String, @JvmSuppressWildcards TokenProvider\>. In
              BackendMicrosoftModule provide "MS" key, in BackendGoogleModule provide "GOOGLE" key.
        * Update manageObservedAccounts: In the loop, check account.providerType. Use the correct
          TokenProvider and API Helper (GraphApiHelper or GmailApiHelper) based on the type. Update
          \_folderStates accordingly.
        * Update refreshAllFolders: Iterate accounts, check type, use correct components from the
          map or injected instances.
    4. **Modify DefaultMessageRepository.kt (:data):**
        * Inject GoogleTokenProvider and GmailApiHelper (or use Map Multibindings).
        * Update setTargetFolder and refreshMessages: Check currentTargetAccount.providerType. Use
          the correct TokenProvider and API Helper based on the type. Update \_messageDataState.
    5. **Update ErrorMapper.kt (:core-common):**
        * Add handling for Google exceptions: ApiException (from Play Services Auth),
          GoogleAuthException (from GoogleAuthUtil). Map common status codes or error types to
          user-friendly messages. Add cases for Ktor exceptions if not already covered broadly in
          Step 1.2.
        * Ensure the repositories in :data use the updated ErrorMapper for both MS and Google error
          paths.
* **Verification:** Run the app. Add both a Microsoft and a Google account. Verify:
    * Both accounts appear correctly in UI lists.
    * Selecting MS folders loads MS messages via Ktor.
    * Selecting Google labels (mapped as folders) loads Google messages via Ktor/Gmail API.
    * Pull-to-refresh works for both types.
    * Adding/Removing both account types works.
    * Simulate errors (network off) for both and check messages.

### **Step 2.7: UI/UX Adjustments & Final Testing**

* **Goal:** Ensure a polished and functional user experience supporting both account providers.
* **Actions:**
    1. **UI Updates:**
        * Modify SettingsScreen or equivalent: Provide distinct "Add Microsoft Account" and "Add
          Google Account" options/buttons.
        * Modify MailDrawerContent: Ensure accounts are grouped or visually distinct if desired.
          Handle display of Gmail "Labels" alongside MS "Folders".
        * Review MessageListContent and MessageListItem: Ensure data from both Message objects (
          mapped from MS or Google) displays correctly.
    2. **Thorough Testing:** Perform end-to-end testing covering all implemented features (
       sign-in/out, folder/label view, message list view, refresh, error handling) for *both*
       Microsoft and Google accounts interactively. Test edge cases.
* **Verification:** The app provides a stable, intuitive, and consistent experience for managing and
  viewing mail from both Microsoft and Google accounts.