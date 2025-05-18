# Resolving Hilt Dependency Cycles and Scope Issues in Melisma Mail

## 1. Introduction

This document outlines a detailed plan to address and resolve critical Dagger Hilt dependency cycles
and scope incompatibilities identified in the Melisma Mail Android application. These issues are
currently major blockers, preventing successful builds and hindering further development. The
resolution of these problems is crucial for stabilizing the application, particularly the work
described in `NIRVANA.MD` concerning the authentication refactoring.

The primary goal is to achieve a consistently buildable application by correctly configuring Hilt
dependencies and scopes, adhering to clean architectural principles.

## 2. Summary of Identified Issues (Context & Findings)

Our investigation, including a review of module dependencies (`build.gradle.kts` files) and key
source code files, has pinpointed the following core issues:

### 2.1. Scope Incompatibility: `@Singleton` Components Injecting Custom `@ApplicationScope`

* **Description:** Components scoped with Hilt's standard `@Singleton` (e.g.,
  `DefaultAccountRepository`, `MicrosoftAccountRepository`) are attempting to inject a
  `CoroutineScope` that is provided with a custom scope annotation, `@ApplicationScope`.
* **Files Involved:**
    * `core-data/src/main/java/net/melisma/core_data/di/Scopes.kt` (Defines `@ApplicationScope`)
    * `app/src/main/java/net/melisma/mail/di/AppProvidesModule.kt` (Provides `CoroutineScope` with
      `@ApplicationScope`)
    * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt` (Injects
      `@ApplicationScope CoroutineScope`)
    *
    `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt` (
    Injects `@ApplicationScope CoroutineScope`)
* **Why it's an issue:** Hilt's scope validation requires that a component can only inject
  dependencies that are unscoped, from its own scope, or from a parent component's scope. While
  `@ApplicationScope` is intended to be an application-wide singleton, Hilt treats it as a distinct
  custom scope. When a `@Singleton` component (tied to `SingletonComponent`) injects an
  `@ApplicationScope` dependency provided within the same `SingletonComponent`, Hilt can flag this
  as a mismatch if it cannot establish a clear hierarchical relationship or equivalence. The
  simplest resolution is to use Hilt's own `@Singleton` scope for application-wide singleton
  dependencies.

### 2.2. Cyclical Dependency 1: `DefaultAccountRepository` -> `GoogleKtorTokenProvider` ->
`AccountRepository`

* **Description:** A dependency cycle exists where `DefaultAccountRepository` indirectly depends on
  `GoogleKtorTokenProvider` (for Google API calls via Ktor), which in turn depends on and calls
  methods on `AccountRepository`. Since `DefaultAccountRepository` is the concrete implementation of
  `AccountRepository`, this creates a cycle.
* **Path of Cycle:**
    1. `DefaultAccountRepository` (in `:data`) makes a call that uses the Ktor client for Google
       services.
    2. The Ktor client's `Auth` plugin uses `GoogleKtorTokenProvider` (in `:backend-google`) to
       fetch/refresh tokens.
    3. `GoogleKtorTokenProvider` is injected with `accountRepository: AccountRepository`.
    4. In its token refresh logic (specifically for `INVALID_GRANT` errors),
       `GoogleKtorTokenProvider` calls `accountRepository.markAccountForReauthentication(...)`.
    5. The `AccountRepository` instance here is `DefaultAccountRepository` itself (due to Hilt
       binding in `DataModule.kt`), completing the cycle.
* **Files Involved:**
    * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
    * `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleKtorTokenProvider.kt`
    * `core-data/src/main/java/net/melisma/core_data/repository/AccountRepository.kt` (Interface)
    * `data/src/main/java/net/melisma/data/di/DataModule.kt` (Binds `AccountRepository` to
      `DefaultAccountRepository`)
* **Root Cause:** The low-level `GoogleKtorTokenProvider` in the `:backend-google` module has a
  dependency on the higher-level `AccountRepository` interface (implemented in `:data`) and calls
  back into it. This violates clear layering and separation of concerns.

### 2.3. Cyclical Dependency 2: `DefaultAccountRepository` injecting itself via
`MicrosoftAccountRepository` field

* **Description:** `DefaultAccountRepository` injects an `AccountRepository` for its
  `microsoftAccountRepository` field. Due to how Hilt bindings are set up, this resolves to
  `DefaultAccountRepository` itself, creating a direct self-injection cycle.
* **Path of Cycle:**
    1. `DefaultAccountRepository` (in `:data`) has a constructor parameter:
       `private val microsoftAccountRepository: AccountRepository`.
    2. `DataModule.kt` (in `:data`) contains the binding:
       `@Binds @Singleton abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository`.
       This tells Hilt that `DefaultAccountRepository` is *the* implementation for
       `AccountRepository`.
    3. When Hilt tries to provide `AccountRepository` for `DefaultAccountRepository`'s
       `microsoftAccountRepository` field, it uses the binding from `DataModule.kt` and injects
       `DefaultAccountRepository` itself.
    4. It's important to note that `MicrosoftAccountRepository` (in `:backend-microsoft`) *also*
       implements `AccountRepository`. The issue is that `DefaultAccountRepository` doesn't specify
       *which* implementation of `AccountRepository` it wants for its Microsoft-specific field.
* **Files Involved:**
    * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
    *
    `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt` (
    Implements `AccountRepository`)
    * `core-data/src/main/java/net/melisma/core_data/repository/AccountRepository.kt` (Interface)
    * `data/src/main/java/net/melisma/data/di/DataModule.kt`
* **Root Cause:** Lack of specific qualification when `DefaultAccountRepository` requests an
  `AccountRepository` implementation intended to be `MicrosoftAccountRepository`.

## 3. Detailed Resolution Plan

The following steps will be implemented in sequence to address these issues.

### Part I: Resolving Scope Incompatibility

* **Objective:** Ensure the `CoroutineScope` intended for application-wide use is provided and
  injected using Hilt's standard `@Singleton` scope.
* **Rationale:** This aligns with Hilt's scoping model, resolving the incompatibility between
  `@Singleton` and the custom `@ApplicationScope` for this specific use case.

* **Step 1.1: Modify `AppProvidesModule.kt`**
    * **File:** `app/src/main/java/net/melisma/mail/di/AppProvidesModule.kt`
    * **Action:** Change the scope annotation for `provideApplicationCoroutineScope` from
      `@ApplicationScope` to `@Singleton`.
    * **Code (Illustrative Change):**
      ```kotlin
      // Before:
      // @ApplicationScope
      // @Provides
      // fun provideApplicationCoroutineScope(...)

      // After:
      @Singleton // Changed from @ApplicationScope
      @Provides
      fun provideApplicationCoroutineScope(
          @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher
      ): CoroutineScope {
          return CoroutineScope(SupervisorJob() + ioDispatcher)
      }
      ```

* **Step 1.2: Modify `DefaultAccountRepository.kt`**
    * **File:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
    * **Action:** Remove the `@ApplicationScope` annotation from the `externalScope` constructor
      parameter. Hilt will now inject the `@Singleton` `CoroutineScope` provided by
      `AppProvidesModule.kt`.
    * **Code (Illustrative Change in Constructor):**
      ```kotlin
      // Before:
      // constructor(
      //     // ...
      //     @ApplicationScope private val externalScope: CoroutineScope,
      //     // ...
      // )

      // After:
      constructor(
          // ... other dependencies
          private val microsoftAccountRepository: AccountRepository, // This will be addressed in Part II
          private val appAuthHelperService: AppAuthHelperService,
          private val googleTokenPersistenceService: GoogleTokenPersistenceService,
          private val externalScope: CoroutineScope, // @ApplicationScope removed
          private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
          private val activeGoogleAccountHolder: ActiveGoogleAccountHolder
      )
      ```

* **Step 1.3: Modify `MicrosoftAccountRepository.kt`**
    * **File:**
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt`
    * **Action:** Remove the `@ApplicationScope` annotation from the `externalScope` constructor
      parameter.
    * **Code (Illustrative Change in Constructor):**
      ```kotlin
      // Before:
      // constructor(
      //     // ...
      //     @ApplicationScope private val externalScope: CoroutineScope,
      //     // ...
      // )

      // After:
      constructor(
          private val microsoftAuthManager: MicrosoftAuthManager,
          private val externalScope: CoroutineScope, // @ApplicationScope removed
          private val microsoftErrorMapper: MicrosoftErrorMapper,
          private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder
      )
      ```

### Part II: Breaking Cyclical Dependency 2 (Microsoft Repository Self-Injection)

* **Objective:** Enable `DefaultAccountRepository` to correctly inject `MicrosoftAccountRepository`
  specifically, instead of injecting itself.
* **Rationale:** Using Hilt qualifiers allows us to have multiple bindings for the same interface (
  `AccountRepository`) and specify which implementation to inject at a particular injection site.

* **Step 2.1: Define `@MicrosoftRepo` Qualifier**
    * **File:** `core-data/src/main/java/net/melisma/core_data/di/Qualifiers.kt`
    * **Action:** Add a new qualifier annotation `@MicrosoftRepo`.
    * **Code:**
      ```kotlin
      package net.melisma.core_data.di

      import javax.inject.Qualifier

      // ... existing qualifiers (@ApiHelperType, @ErrorMapperType) ...

      @Qualifier
      @Retention(AnnotationRetention.BINARY)
      annotation class MicrosoftRepo

      @Qualifier
      @Retention(AnnotationRetention.BINARY)
      annotation class GoogleRepo // Add for future use or consistency, though not strictly needed for this cycle
      ```

* **Step 2.2: Create `BackendMicrosoftBindsModule.kt` for Qualified Binding**
    * **File:**
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/BackendMicrosoftBindsModule.kt` (
      New File)
    * **Action:** Create a new Hilt module to bind `MicrosoftAccountRepository` as an
      `AccountRepository` qualified with `@MicrosoftRepo`.
    * **Code:**
      ```kotlin
      package net.melisma.backend_microsoft.di

      import dagger.Binds
      import dagger.Module
      import dagger.hilt.InstallIn
      import dagger.hilt.components.SingletonComponent
      import net.melisma.backend_microsoft.repository.MicrosoftAccountRepository
      import net.melisma.core_data.di.MicrosoftRepo // Import the qualifier
      import net.melisma.core_data.repository.AccountRepository
      import javax.inject.Singleton

      @Module
      @InstallIn(SingletonComponent::class)
      abstract class BackendMicrosoftBindsModule {

          @Binds
          @Singleton
          @MicrosoftRepo // Apply the qualifier
          abstract fun bindMicrosoftAccountRepository(
              impl: MicrosoftAccountRepository
          ): AccountRepository
      }
      ```
    * **Note:** `BackendMicrosoftModule.kt` (the existing object module) will remain for `@Provides`
      methods. This new `abstract class` module is specifically for `@Binds`.

* **Step 2.3: Modify `DefaultAccountRepository.kt` to Use Qualified Injection**
    * **File:** `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
    * **Action:** Annotate the `microsoftAccountRepository` constructor parameter with
      `@MicrosoftRepo`.
    * **Code (Illustrative Change in Constructor):**
      ```kotlin
      // Add import:
      import net.melisma.core_data.di.MicrosoftRepo
      // ...
      constructor(
          @MicrosoftRepo private val microsoftAccountRepository: AccountRepository, // Qualifier added
          private val appAuthHelperService: AppAuthHelperService,
          private val googleTokenPersistenceService: GoogleTokenPersistenceService,
          private val externalScope: CoroutineScope,
          private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
          private val activeGoogleAccountHolder: ActiveGoogleAccountHolder
      )
      ```

* **Step 2.4: Verify `DataModule.kt`**
    * **File:** `data/src/main/java/net/melisma/data/di/DataModule.kt`
    * **Action:** No changes needed here. The existing unqualified binding is correct:
      ```kotlin
      @Binds
      @Singleton
      abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository
      ```
      This binding correctly states that `DefaultAccountRepository` is the default, unqualified
      implementation for `AccountRepository`.

### Part III: Breaking Cyclical Dependency 1 (Google Provider Cycle)

* **Objective:** Decouple `GoogleKtorTokenProvider` from `AccountRepository` by changing how
  re-authentication needs are signaled.
* **Rationale:** Low-level components like token providers should not call back into higher-level
  repositories. They should report status (success, failure, specific conditions) to their callers,
  which then decide on further actions. This improves modularity and testability.

* **Step 3.1: Define `GoogleNeedsReauthenticationException`**
    * **File:** `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleExceptions.kt` (
      New File)
    * **Action:** Create a custom exception to signal that Google re-authentication is required.
    * **Code:**
      ```kotlin
      package net.melisma.backend_google.auth

      class GoogleNeedsReauthenticationException(
          val accountId: String,
          message: String = "Account $accountId needs re-authentication with Google.",
          cause: Throwable? = null
      ) : Exception(message, cause)
      ```

* **Step 3.2: Modify `GoogleKtorTokenProvider.kt`**
    * **File:**
      `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleKtorTokenProvider.kt`
    * **Actions:**
        1. Remove the `AccountRepository` injection from the constructor.
        2. In the `catch` block for `AuthorizationException` where `INVALID_GRANT` is handled, throw
           the new `GoogleNeedsReauthenticationException` instead of calling
           `accountRepository.markAccountForReauthentication()`.
    * **Code (Illustrative Changes):**
      ```kotlin
      // Remove import for AccountRepository
      // import net.melisma.core_data.repository.AccountRepository

      // Modify constructor:
      // Before:
      // class GoogleKtorTokenProvider @Inject constructor(
      //     private val tokenPersistenceService: GoogleTokenPersistenceService,
      //     private val appAuthHelperService: AppAuthHelperService,
      //     private val activeAccountHolder: ActiveGoogleAccountHolder,
      //     private val accountRepository: AccountRepository // REMOVE THIS LINE
      // )
      // After:
      class GoogleKtorTokenProvider @Inject constructor(
          private val tokenPersistenceService: GoogleTokenPersistenceService,
          private val appAuthHelperService: AppAuthHelperService,
          private val activeAccountHolder: ActiveGoogleAccountHolder
          // accountRepository removed
      )

      // Modify catch block in getBearerTokens():
      // Inside the catch (ex: AuthorizationException) block:
      // ...
      if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
          ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code) {
          Timber.tag(TAG)
              .e("INVALID_GRANT error during token refresh for $accountId. Clearing AuthState and signaling re-auth need.")
          tokenPersistenceService.clearTokens(
              accountId,
              removeAccount = false // Keep account entry, just clear tokens
          )
          // REMOVE:
          // accountRepository.markAccountForReauthentication(
          //     accountId,
          //     Account.PROVIDER_TYPE_GOOGLE
          // )
          // ADD:
          throw GoogleNeedsReauthenticationException(accountId = accountId, cause = ex)
      }
      // ...
      ```

* **Step 3.3: Modify `GmailApiHelper.kt` to Handle the New Exception**
    * **File:** `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
    * **Actions:**
        1. Inject `AccountRepository` into `GmailApiHelper`. This is an acceptable dependency at
           this layer.
        2. In each public suspend function that makes Ktor calls (e.g., `getMailFolders`,
           `getMessagesForFolder`, `getThreadDetails`, etc.), add a specific `catch` block for
           `GoogleNeedsReauthenticationException` before the generic `catch (e: Exception)`.
        3. Inside this new `catch` block, call
           `accountRepository.markAccountForReauthentication(e.accountId, Account.PROVIDER_TYPE_GOOGLE)`.
        4. After calling `markAccountForReauthentication`, either re-throw the exception, map it to
           a specific `Result.failure` with a user-friendly message, or let it fall through to the
           generic error mapping if appropriate. For simplicity, initially, we can map it using the
           existing `errorMapper`.
    * **Code (Illustrative Changes):**
      ```kotlin
      // Add import for AccountRepository and Account model
      import net.melisma.core_data.repository.AccountRepository
      import net.melisma.core_data.model.Account
      import net.melisma.backend_google.auth.GoogleNeedsReauthenticationException // Import new exception

      // Modify constructor:
      // Before:
      // class GmailApiHelper @Inject constructor(
      //     @GoogleHttpClient private val httpClient: HttpClient,
      //     private val errorMapper: ErrorMapperService
      // )
      // After:
      @Singleton
      class GmailApiHelper @Inject constructor(
          @GoogleHttpClient private val httpClient: HttpClient,
          private val errorMapper: ErrorMapperService,
          private val accountRepository: AccountRepository // Inject AccountRepository
      ) : MailApiService

      // Modify relevant API methods, for example getMailFolders():
      // override suspend fun getMailFolders(): Result<List<MailFolder>> {
      //     return try {
      //         // ... Ktor call ...
      //     } catch (e: GoogleNeedsReauthenticationException) { // ADD THIS BLOCK
      //         Log.w(TAG, "Google account ${e.accountId} needs re-authentication during getMailFolders.", e)
      //         try { // Use try-catch for the suspend function call
      //             accountRepository.markAccountForReauthentication(e.accountId, Account.PROVIDER_TYPE_GOOGLE)
      //         } catch (markEx: Exception) {
      //             Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
      //             // Potentially log this failure but proceed with original error mapping
      //         }
      //         val mappedDetails = errorMapper.mapExceptionToErrorDetails(e) // Or create a more specific message
      //         Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
      //     } catch (e: Exception) { // Existing generic catch block
      //         Log.e(TAG, "Exception fetching mail folders", e)
      //         val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
      //         Result.failure(Exception(mappedDetails.message))
      //     }
      // }
      // Apply similar catch blocks to other public API methods in GmailApiHelper making Ktor calls.
      ```
        * **Note on `accountRepository` in `GmailApiHelper`:** `AccountRepository` itself will be
          `DefaultAccountRepository`. This dependency from `GmailApiHelper` (in `:backend-google`)
          to `AccountRepository` (interface in `:core-data`, implemented by `:data`) is acceptable
          as `:backend-google` already depends on `:core-data`. The key is that the *very low-level*
          `GoogleKtorTokenProvider` is no longer calling back.

## 4. Verification Steps (Post-Implementation)

1. **Clean Build:** Execute `./gradlew clean build` in the terminal. The build should complete
   successfully without Hilt dependency or scope errors.
2. **Run Unit Tests:** Execute `./gradlew testDebugUnitTest` (or the relevant test task for all
   modules). All existing tests should pass. New tests might be needed for the changed logic,
   especially around the re-authentication signaling.
3. **Manual Testing (Recommended):**
    * Test Google sign-in and sign-out.
    * Test Microsoft sign-in and sign-out.
    * If possible, simulate a token expiry or invalid grant for Google to verify that the
      `GoogleNeedsReauthenticationException` is caught and the account is marked for
      re-authentication (check logs or UI indicators if they exist).

## 5. Review of Ancillary Points

* **`:app` module's direct MSAL dependency:** As investigated and confirmed, this dependency (
  `implementation(libs.microsoft.msal)` in `app/build.gradle.kts`) is justified and required due to
  the declaration of `com.microsoft.identity.client.BrowserTabActivity` in the
  `app/src/main/AndroidManifest.xml`. This is standard practice for MSAL. No changes are needed
  here.

This detailed plan should guide the implementation to resolve the Hilt issues effectively. 