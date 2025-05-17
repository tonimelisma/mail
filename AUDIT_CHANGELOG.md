# Audit Changelog and Implementation Notes

This document tracks the changes made based on the `AUDIT_REPORT.md` and any new findings or
deviations from the initial plan.

## Date: (Today's Date) - Initial Implementation Phase

### Implementation of Recommendations from `AUDIT_REPORT.md`

#### 1. Addressing R-CRITICAL-01: Fix Test Failure Ignoring & Initial Test Failures

* **Action:** Modified `app/build.gradle.kts` to comment out `it.ignoreFailures = true` in the
  `testOptions.unitTests.all` block.
    * **File:** `app/build.gradle.kts`
    * **Status:** Completed.
* **Action:** Attempted to run all unit tests (`./gradlew testDebugUnitTest`).
    * **Status:** Blocked.
    * **Finding/Surprise:** The pre-existing build issue (Audit Finding F-BUILD-01, Recommendation
      R-HIGH-01) related to `:backend-microsoft:kspDebugKotlin FAILED` (specifically
      `MicrosoftTokenPersistenceService` not being resolved by KSP for `MicrosoftAccountRepository`)
      prevented unit tests from running.
    * **Deviation:** The original plan was to run tests and then fix them. Due to the build blockage
      and user instruction *not* to fix the build issue, this step could not be fully executed.
* **Action:** Modified `app/src/test/java/net/melisma/mail/MainViewModelTest.kt` to remove
  `try-catch` blocks in `setUp()` and individual tests that were suppressing `NoClassDefFoundError`
  and other `Throwable` exceptions.
    * **File:** `app/src/test/java/net/melisma/mail/MainViewModelTest.kt`
    * **Status:** Completed.
    * **Note:** Verification of these changes by running tests is blocked by the build issue.

#### 2. Addressing R-MED-01: Complete and Enhance Unit Test Coverage

* **Enhance `MainViewModelTest.kt` Coverage:**
    * **Status:** Skipped as per user instruction due to an impending major refactoring of
      `MainViewModel`.
* **Implement Tests for `GmailApiHelper.kt`:**
    * **File:** `backend-google/src/test/java/net/melisma/backend_google/GmailApiHelperTest.kt`
    * **Action:**
        * Removed the placeholder test.
        * Added Ktor `MockEngine` setup, JSON test data for labels (valid, empty, error).
        * Implemented comprehensive tests for `getMailFolders` (success, empty list, API error,
          network error), including checks for URL, parameters, and response mapping.
        * Added placeholder test stubs for other methods outlined in the audit report's TODOs:
          `getMessagesForFolder`, `fetchMessageDetails`, `getMessagesForThread`, `markMessageRead`,
          `markMessageUnread`, `deleteMessage`, `moveMessage`.
    * **Status:** Completed.
* **Add Tests for `GraphApiHelper.kt` Mail Modification Actions:**
    * **File:**
      `backend-microsoft/src/test/java/net/melisma/backend_microsoft/GraphApiHelperTest.kt`
    * **Action:**
        * Added tests for `getMessageById` (success, error) as a foundational read operation.
        * Added tests for `markMessageRead` (success for read, success for unread, API error),
          including HTTP method checks and response validation. TODOs were added to check request
          bodies.
        * Added tests for `deleteMessage` (success, API error), including HTTP method checks.
        * Added tests for `moveMessage` (success, API error), including HTTP method checks and
          response validation. A TODO was added to check the request body.
    * **Status:** Completed.

#### 3. Addressing R-LOW-02: Review `:app` Module's Direct Backend Dependencies

* **Analysis:**
    * `implementation(project(":backend-microsoft"))` and `implementation(libs.microsoft.msal)` in
      `app/build.gradle.kts`:
        * Grep search found no direct Kotlin/Java imports from `net.melisma.backend_microsoft.*` in
          `app/src/main`.
        * `AndroidManifest.xml` declares `com.microsoft.identity.client.BrowserTabActivity`.
        * **Conclusion:** The `libs.microsoft.msal` dependency is justified for
          `BrowserTabActivity`. The `project(":backend-microsoft")` might be for transitive
          dependencies or other manifest declarations (like `MicrosoftAuthenticatorService`), which
          is acceptable if direct code coupling is avoided.
    * `implementation(project(":backend-google"))` in `app/build.gradle.kts`:
        * Grep search found `import net.melisma.backend_google.auth.AppAuthHelperService` in
          `app/src/main/java/net/melisma/mail/MainViewModel.kt`.
        * Usage was to access `AppAuthHelperService.GMAIL_SCOPES`.
* **Refactoring Action:**
    * **Moved Google OAuth Scopes:**
        * The constants `GMAIL_API_SCOPE_BASE` and `GMAIL_SCOPES_FOR_LOGIN` (derived from
          `AppAuthHelperService.GMAIL_SCOPES`) were added to
          `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`.
        * A new public method `getGoogleLoginScopes(): List<String>` was added to
          `DefaultAccountRepository.kt`.
        * `DefaultAccountRepository.getAuthenticationIntentRequest()` was updated to use this new
          internal method for Google scopes.
    * **Updated `MainViewModel.kt`:**
        * Removed the direct import of `net.melisma.backend_google.auth.AppAuthHelperService`.
        * Removed the usage of `AppAuthHelperService.GMAIL_SCOPES`.
        * The `addAccount` method in `MainViewModel.kt` now calls
          `defaultAccountRepository.getGoogleLoginScopes()` to retrieve scopes for Google sign-in.
    * **Files Modified:**
        * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
        * `app/src/main/java/net/melisma/mail/MainViewModel.kt`
    * **Status:** Completed. This significantly reduces direct coupling from `:app` to
      `:backend-google` for this piece of logic.

### Surprises and New Findings During Implementation

* **Build Issue is a Hard Blocker:** The KSP issue in `:backend-microsoft` is critical. The
  inability to run *any* unit tests severely hampers verification of fixes (like removing error
  suppression in `MainViewModelTest.kt`) and assessing the true state of test coverage.
* **MSAL Dependency in `:app`:** The `implementation(libs.microsoft.msal)` in `:app` is primarily
  for the `BrowserTabActivity` manifest declaration, not for direct Kotlin/Java class usage in
  `app/src/main`. This is a common and acceptable pattern for MSAL. The
  `project(":backend-microsoft")` dependency in `:app` did not show direct class usages from its own
  packages in `app/src/main`, suggesting its role might be as a carrier for MSAL's transitive needs
  or for manifest-declared services from that module.
* **`DefaultAccountRepository` Complexity:** This file in the `:data` module is large and contains
  significant commented-out legacy code. It already had a dependency on `AppAuthHelperService` from
  `:backend-google`, making the scope relocation straightforward from a dependency graph
  perspective.
* **`REDIRECT_URI_APP_AUTH` in `DefaultAccountRepository`:** During the modification of
  `getAuthenticationIntentRequest` in `DefaultAccountRepository.kt` for R-LOW-02, it was noted that
  `Uri.parse(REDIRECT_URI_APP_AUTH)` is used. The definition and correctness of this constant were
  not part of this specific audit task but are important for Google OAuth functionality.

### Deviations from Original Plan

* **Skipped Test Execution for R-CRITICAL-01:** Due to the KSP build failure and user instruction
  not to fix it, test execution steps were bypassed. Code modifications were made, but not verified
  through test runs.
* **Skipped `MainViewModel` Test Implementation (R-MED-01):** User explicitly requested to skip
  implementing new tests for `MainViewModel` due to upcoming refactoring.
* **No Build Fix (R-HIGH-01):** Explicitly instructed not to fix the build issue. This is the most
  significant deviation as it impacts other steps.

### Overall Status

Progress was made on code cleanup for test reliability (R-CRITICAL-01 code changes), significantly
improving test structure and adding new tests for API helpers (R-MED-01 for `GmailApiHelper` and
`GraphApiHelper`), and refactoring direct backend dependencies in the app module (R-LOW-02).
However, the inability to run tests due to the outstanding build issue means that the actual impact
of test-related changes (R-CRITICAL-01) cannot be confirmed. 