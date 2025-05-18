# Changelog

**Date:** Current Date

This changelog details significant updates and refactorings made to the codebase and documentation
during the recent session.

## Architectural & Dependency Updates

### 1. `androidx.credentials.CredentialManager` Cleanup

Based on a thorough review, `androidx.credentials.CredentialManager` was found to be unused in the
production Google Sign-In flow, which exclusively uses AppAuth orchestrated by `GoogleAuthManager`.

* **`backend-google/build.gradle.kts`:**
    * Removed `testImplementation(libs.androidx.credentials)`. The dependency was present for
      testing exceptions but no active test code was found utilizing it.
    * Removed `testImplementation(libs.google.id)`. Similar to the above, this dependency (for
      `GoogleIdTokenParsingException`) was found to be unused in active test code.
    * Removed associated comments that referred to these test dependencies.

* **`backend-google/src/main/AndroidManifest.xml`:**
    * Removed the `<uses-permission android:name="android.permission.CREDENTIAL_MANAGER_SERVICE" />`
      permission. As `CredentialManager` is not used in the runtime application for Google Sign-In,
      this permission was deemed unnecessary for the `:backend-google` module.

* **`backend-google/src/test/java/net/melisma/backend_google/errors/GoogleErrorMapperTest.kt`:**
    * Removed obsolete comments at the beginning of the file that referred to importing
      `androidx.credentials` exceptions. These comments were no longer relevant after the removal of
      the test dependencies.

### 2. Build Configuration Cleanup

* **`app/build.gradle.kts`:**
    * Modified an informational comment within the `testOptions.unitTests.all` block. The comment
      `// it.ignoreFailures = true // TODO: Removed as per R-CRITICAL-01` was changed to
      `// it.ignoreFailures = true // Removed as per R-CRITICAL-01` to remove the
      no-longer-actionable `TODO` prefix.

## Documentation Updates

### 1. Creation of `DESIGN2.md`

* A new architectural design document, `DESIGN2.md`, was created from scratch.
* **Purpose:** To provide a completely up-to-date and accurate representation of the project's
  architecture, particularly reflecting the refined authentication flows for both Google (AppAuth
  with `GoogleAuthManager`) and Microsoft (MSAL with `MicrosoftAuthManager` and
  `MicrosoftTokenPersistenceService`).
* This new document incorporates all learnings and refactorings discussed, ensuring future
  development is based on the current state of the codebase.

### 2. Identification of Obsolete Code (Manual Action Recommended)

* **`data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`:**
    * During the review process, several large blocks of commented-out code were identified as
      obsolete (e.g., old `onAuthStateChanged` method, old `addAccount` and `removeAccount` methods,
      and their provider-specific helpers).
    * While automated removal was not consistently successful, these sections are confirmed to be no
      longer in use and can be safely **manually deleted** to improve code readability and
      maintainability.

## Summary of Impact

These changes contribute to a cleaner, more maintainable codebase by removing unused dependencies
and permissions, clarifying build configurations, and providing up-to-date architectural
documentation. The primary impact is a more accurate representation of the Google authentication
flow, now correctly documented as solely using AppAuth managed by `GoogleAuthManager`. 