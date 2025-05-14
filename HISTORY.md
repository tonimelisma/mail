# Build Troubleshooting and Refactoring History

This document summarizes the key steps taken to troubleshoot and refactor the build process,
ultimately achieving a successful build without tests.

## Summary of Changes:

1. **Initial Build Failure & `GmailThread` Fix:**
    * The first attempt to build without tests (
      `./gradlew assembleDebug -x test -x testDebugUnitTest -x connectedAndroidTest`) failed due to
      an unresolved `GmailThread` in
      `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`.
    * **Change:** Added the necessary import: `import net.melisma.backend_google.model.GmailThread;`
      to `GmailApiHelper.kt`.

2. **Kapt to KSP Migration for Hilt (App Module):**
    * The build then failed with a Kapt error (`Could not load module <Error module>`).
    * To address this, especially with Kotlin 2.0.0, Hilt was migrated from Kapt to KSP in the `app`
      module:
        * **`gradle/libs.versions.toml`:**
            * Added KSP version: `ksp = "2.0.0-1.0.22"` to `[versions]`.
            * Added KSP plugin alias:
              `kotlin-ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }` to `[plugins]`.
        * **`app/build.gradle.kts`:**
            * Replaced Kapt plugin `id("org.jetbrains.kotlin.kapt")` with KSP plugin
              `alias(libs.plugins.kotlin.ksp)`.
            * Changed Hilt compiler dependency from `kapt(libs.hilt.compiler)` to
              `ksp(libs.hilt.compiler)`.
            * Removed the `kapt { ... }` configuration block.

3. **KSP Plugin Declaration Scope Fix:**
    * The build failed again with a Hilt/KSP classloader issue, indicating that KSP and Hilt plugins
      were not declared in the same scope.
    * **Change (Root `build.gradle.kts`):** Declared the KSP plugin with `apply false`:
      `alias(libs.plugins.kotlin.ksp) apply false`.

4. **Resolving Unresolved References in UI Code (`app` module):**
    * The build encountered compilation errors in `MainActivity.kt` and `MainViewModel.kt` due to
      unresolved references (`ViewMode`, `ThreadDataState`, `MailThread`, Material `Icons`).
    * **Changes (`app/src/main/java/net/melisma/mail/MainViewModel.kt`):**
        * Added imports: `import net.melisma.core_data.model.MailThread;` and
          `import net.melisma.core_data.model.ThreadDataState;`.
        * Moved the `enum class ViewMode` definition to appear *before* the
          `data class MainScreenState` that uses it.
    * **Changes (`app/src/main/java/net/melisma/mail/MainActivity.kt`):**
        * Added specific icon imports: `import androidx.compose.material.icons.filled.Forum;` and
          `import androidx.compose.material.icons.automirrored.filled.List;` (this replaced earlier
          general and non-mirrored imports).
        * Corrected direct usage of `ViewMode` (e.g., `state.currentViewMode == ViewMode.THREADS`)
          instead of the previously incorrect `MainViewModel.ViewMode`.
        * Added import: `import net.melisma.core_data.model.ThreadDataState;`.

5. **Resolving Hilt Duplicate Bindings:**
    * The build failed with Dagger/Hilt duplicate binding errors. `AccountRepository`,
      `FolderRepository`, and `MessageRepository` were found to be bound in both `:data:DataModule`
      and `:app:RepositoryModule`.
    * **Change (`app/src/main/java/net/melisma/mail/di/RepositoryModule.kt`):** Removed the
      duplicate bindings for `AccountRepository`, `FolderRepository`, and `MessageRepository` (and
      their corresponding unused imports). The binding for `ThreadRepository` was retained as it was
      unique to this module.

6. **Build Success & Deprecation Warning Fix:**
    * After resolving the duplicate bindings, the command
      `./gradlew assembleDebug -x test -x testDebugUnitTest -x connectedAndroidTest` was *
      *successful**.
    * A deprecation warning for `Icons.Filled.List` was noted in the successful build's output.
    * **Change (`app/src/main/java/net/melisma/mail/MainActivity.kt`):** Updated the import from
      `androidx.compose.material.icons.filled.List` to
      `androidx.compose.material.icons.automirrored.filled.List` and updated its usage to
      `Icons.AutoMirrored.Filled.List`.

The application now builds successfully without running test tasks. 