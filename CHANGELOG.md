# Changelog

## [Unreleased] - 2025-06-15

### Added
- **Debug Feature: Clear All Microsoft Accounts**
  - To address persistent `AdalKey` decryption errors logged by MSAL, a new debug feature has been added to completely clear all cached Microsoft accounts.
  - `MicrosoftAuthManager`: A new `signOutAll()` function was added to iterate through and remove every account known to MSAL, and to clear all related local data from the database.
  - `AccountDao`: A `deleteAllMicrosoftAccounts()` query was added to support the complete removal of local Microsoft account data.
  - `AccountRepository`: The interface was updated with `signOutAllMicrosoftAccounts()` and implemented in `MicrosoftAccountRepository` and `DefaultAccountRepository`.
  - `SignOutAllMicrosoftAccountsUseCase`: A new use case was created to expose this functionality to the UI layer.
  - `MainViewModel`: Was updated with a `signOutAllMicrosoftAccounts()` function to trigger the use case.
  - `SettingsScreen`: A new "DEBUG" section was added with a "Clear All Microsoft Accounts (Cache)" button to allow developers to easily clear the cache and resolve the `AdalKey` warnings.

### Blocked
- **Build Failures in `backend-microsoft` module**
  - The implementation of the "Clear All Accounts" feature introduced several compilation errors in the `backend-microsoft` module.
  - **Primary Errors:** Unresolved references for `GenericSignOutAllResult` and `NotInitialized`, and function signature mismatches between the `AccountRepository` interface and its implementation in `MicrosoftAccountRepository`.
  - **Root Cause of Blockage:** I was unable to resolve these compilation errors due to persistent issues with the internal tooling used for applying file edits. The necessary corrections were repeatedly attempted but not successfully applied to the source files, preventing the build from succeeding. Further investigation into the tooling is required to move forward.
- **Investigation of `CALLBACK_AVAILABLE` errors postponed**
  - The investigation into the `callback not found for CALLBACK_AVAILABLE message` warnings was postponed to prioritize fixing the build. 