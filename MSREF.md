# Refactoring Plan: Relocate MicrosoftAuthManager to :backend-microsoft

**Goal:** Move all MSAL-specific authentication logic (`MicrosoftAuthManager` and related code)
entirely within the `:backend-microsoft` module, eliminate the `:feature-auth` module, and clean up
dependencies for better separation of concerns and backend agnosticism.

**Prerequisites:** Project uses Gradle modules `:app`, `:feature-auth`, `:core-data`,
`:backend-microsoft`, and Hilt for Dependency Injection. The app currently builds and authentication
functions correctly.

---

## Step 1: Physically Move Authentication Files

**Action:**

1. In your project structure, move the file:
    * **From:** `feature-auth/src/main/java/net/melisma/feature_auth/MicrosoftAuthManager.kt`
    * **To:**
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt`
      (Create the `auth` package within `backend-microsoft`'s java sources if it doesn't exist).
2. Open the moved `MicrosoftAuthManager.kt` file.
3. Update its package declaration at the top to:
   ```kotlin
   package net.melisma.backend_microsoft.auth
   ```
4. Open the following files in the `:backend-microsoft` module:
    *
   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt`
    *
   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/datasource/MicrosoftTokenProvider.kt`
5. In both files, update the import statements that refer to classes/interfaces previously in
   `net.melisma.feature_auth` to use the new `net.melisma.backend_microsoft.auth` package. This
   includes:
    * `MicrosoftAuthManager`
    * `AddAccountResult`
    * `RemoveAccountResult`
    * `AcquireTokenResult`
    * `AuthStateListener`

**Verification:**

1. Sync Gradle if prompted by the IDE.
2. Attempt to build the project (`Build` > `Make Project`). Resolve any immediate import or package
   resolution errors within the `:backend-microsoft` module.
    * *Note:* The project might not *fully* link or run yet due to DI and cross-module dependency
      issues, but the code *within* `:backend-microsoft` referencing the moved files should now
      compile.

---

## Step 2: Refactor Hilt Provider for `MicrosoftAuthManager`

**Context:** `MicrosoftAuthManager` needs both `Context` and the resource ID `R.raw.auth_config`.
The resource ID belongs to the `:app` module. We need to provide the ID from `:app` to
`:backend-microsoft` where the manager now lives.

**Action:**

1. **Define an interface in `:backend-microsoft`** to provide the config:
    * Create a new file `AuthConfigProvider.kt` in
      `backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/` (or a similar suitable
      package).
    * Add the following content:
        ```kotlin
        package net.melisma.backend_microsoft.di // Or your chosen package

        import androidx.annotation.RawRes

        /** Interface to provide backend-specific configuration, like the MSAL config file ID */
        interface AuthConfigProvider {
            @RawRes fun getMsalConfigResId(): Int
        }
        ```
2. **Modify `MicrosoftAuthManager`** (in `:backend-microsoft`) to use the interface:
    * Change its constructor signature:
        ```kotlin
        // Add AuthConfigProvider parameter, keep Context
        class MicrosoftAuthManager(
            val context: Context,
            // Inject the provider instead of the raw ID
            authConfigProvider: AuthConfigProvider
        ) {
            // Use the provider to get the ID internally
            @RawRes private val configResId: Int = authConfigProvider.getMsalConfigResId()
            // ... rest of the class remains the same
        }
        ```
    * Update the `initializeMsal` call inside `MicrosoftAuthManager` to use this `configResId`
      field.
3. **Modify `BackendMicrosoftModule.kt`** (in `:backend-microsoft`):
    * Add the provider function for `MicrosoftAuthManager` inside the `companion object`:
        ```kotlin
        // Add necessary imports: Context, Singleton, Provides, AuthConfigProvider, MicrosoftAuthManager
        import android.content.Context
        import androidx.annotation.RawRes // If needed by AuthConfigProvider if defined elsewhere
        import dagger.hilt.android.qualifiers.ApplicationContext // Needed if not already imported
        import net.melisma.backend_microsoft.auth.MicrosoftAuthManager // Import from new location

        // ... inside companion object ...

        /** Provides the singleton instance of [MicrosoftAuthManager]. */
        @Provides
        @Singleton
        fun provideMicrosoftAuthManager(
            @ApplicationContext appContext: Context, // Get application context via Hilt
            authConfigProvider: AuthConfigProvider // Inject the config provider
        ): MicrosoftAuthManager {
            // Construct with context and the provider interface
            return MicrosoftAuthManager(
                context = appContext,
                authConfigProvider = authConfigProvider
            )
        }
        ```
4. **Modify `RepositoryModule.kt`** (in `:app`):
    * **Remove** the entire `provideMicrosoftAuthManager` function.
    * **Add** a provider for the *new interface* `AuthConfigProvider`:
        ```kotlin
        // Add necessary imports: Provides, Singleton, AuthConfigProvider, R
        import net.melisma.backend_microsoft.di.AuthConfigProvider // Import the new interface
        import net.melisma.mail.R // Import your app's R class

        // ... inside companion object ...

        /** Provides the implementation for AuthConfigProvider, sourcing the ID from app resources. */
        @Provides
        @Singleton
        fun provideAuthConfigProvider(): AuthConfigProvider {
            return object : AuthConfigProvider {
                override fun getMsalConfigResId(): Int = R.raw.auth_config // Provide the actual ID
            }
        }
        ```

**Verification:**

1. Sync Gradle.
2. Build the project (`Build` > `Make Project`). It should compile successfully now as Hilt can wire
   the dependencies across modules.
3. Run the app on a device/emulator. It *should* launch, but authentication might fail if the Gradle
   dependencies (next step) are not yet correct.

---

## Step 3: Adjust Gradle Module Dependencies

**Action:**

1. Open the Gradle build file for the **`:app`** module (e.g., `app/build.gradle.kts`).
2. **Remove** the dependency on `:feature-auth`:
   ```diff
   - implementation(project(":feature-auth"))
   ```
3. Ensure `:app` still depends on `:core-data` and Hilt. (Hilt's annotation processor usually
   handles the cross-module wiring to `:backend-microsoft` as long as the modules are included in
   the project).
4. Open the Gradle build file for the **`:backend-microsoft`** module (e.g.,
   `backend-microsoft/build.gradle.kts`).
5. Ensure it does **NOT** have any dependency on `:feature-auth`.
6. Ensure it *does* depend on `:core-data`:
   ```kotlin
   implementation(project(":core-data"))
   // Also ensure Hilt dependencies are present if needed within this module
   ```
7. Sync Gradle files (`File` > `Sync Project with Gradle Files`).

**Verification:**

1. Clean the project (`Build` > `Clean Project`).
2. Rebuild the project (`Build` > `Make Project`). It must compile without errors.

---

## Step 4: Test Application Functionality

**Action:**

1. Run the application on an emulator or physical device.
2. Thoroughly test all authentication-related flows:
    * Launch the app when signed out -> See Sign-in prompt/UI.
    * Tap "Add Account" / Sign In -> Complete MSAL interactive flow.
    * Verify successful sign-in (e.g., folders load, account shown in drawer/settings).
    * Close and reopen the app -> Verify it remains signed in (silent auth works).
    * Go to Settings/Account Management -> Sign Out.
    * Verify successful sign-out (e.g., back to sign-in prompt).
    * Attempt an action requiring auth while signed out -> Verify it prompts for sign-in or handles
      it gracefully.

**Verification:**

1. Confirm that all authentication features work exactly as they did before the refactoring. Check
   Logcat for any new errors or warnings, especially from Hilt or `MicrosoftAuthManager`.

---

## Step 5: Delete `:feature-auth` Module

**Action:**

1. Ensure all previous steps are complete and verified.
2. In Android Studio's "Project" view (not "Android" view), locate the `:feature-auth` module
   directory.
3. Right-click on the `feature-auth` directory and select `Delete...`. Confirm the deletion.
4. Open the root `settings.gradle.kts` (or `settings.gradle`) file in your project's root directory.
5. **Remove** the line that includes the module:
   ```diff
   - include(":feature-auth")
   ```
6. Sync Gradle files (`File` > `Sync Project with Gradle Files`).

**Verification:**

1. Clean and rebuild the project. Ensure it still compiles successfully.
2. Run the app one last time and perform a quick auth check (sign in/out) to ensure nothing was
   broken by the module removal.
3. Confirm the `feature-auth` directory is gone from your project structure and file system.

---

**Completion:** The `MicrosoftAuthManager` and all related MSAL logic are now correctly located
within the `:backend-microsoft` module. The `:app` module no longer depends on `:feature-auth`, and
the overall architecture has improved separation of concerns.
