# Refactoring Summary: REQ-CACHE-003 & Build Fixes

This document summarizes the refactoring work undertaken for REQ-CACHE-003 (Selective Offline Download for Attachments/Bodies) and the subsequent efforts to resolve build errors.

## 1. Relevant Files Edited

The following files were modified during this process. Note that not all edits resulted in a stable, buildable state.

**Core Data (`:core-data`):**
*   `core-data/src/main/java/net/melisma/core_data/preferences/UserPreferencesRepository.kt`
*   `core-data/src/main/java/net/melisma/core_data/preferences/model/UserPreferences.kt` (conceptual, via `DownloadPreference` enum addition)
*   `core-data/src/main/java/net/melisma/core_data/connectivity/NetworkMonitor.kt` (interface enhanced)
*   `core-data/src/main/java/net/melisma/core_data/connectivity/AndroidNetworkMonitor.kt` (implementation updated)
*   `core-data/src/main/AndroidManifest.xml` (permission checked, was already present)

**Data (`:data` module - Workers):**
*   `data/src/main/java/net/melisma/data/sync/workers/MessageBodyDownloadWorker.kt`
*   `data/src/main/java/net/melisma/data/sync/workers/AttachmentDownloadWorker.kt`
*   `data/src/main/java/net/melisma/data/sync/workers/FolderContentSyncWorker.kt`

**Domain (`:domain` module - UseCases):**
*   `domain/src/main/java/net/melisma/domain/data/GetMessageDetailsUseCase.kt` (read, used by ViewModel)
*   `domain/src/main/java/net/melisma/domain/data/GetThreadDetailsUseCase.kt` (read, logic in ViewModel adapted to its actual signature)

**Mail (UI - `:mail` module):**
*   `mail/src/main/java/net/melisma/mail/ui/settings/MainViewModel.kt`
*   `mail/src/main/java/net/melisma/mail/ui/settings/SettingsScreen.kt`
*   `mail/src/main/res/values/strings.xml`
*   `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt`
*   `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailScreen.kt`
*   `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailUIState.kt` (created/verified)
*   `mail/src/main/java/net/melisma/mail/ui/threaddetail/ThreadDetailViewModel.kt`
*   `mail/src/main/java/net/melisma/mail/ui/threaddetail/FullMessageDisplayUnit.kt`
*   `mail/src/main/java/net/melisma/mail/ui/threaddetail/BodyLoadingState.kt`
*   `mail/src/main/java/net/melisma/mail/ui/threaddetail/ThreadMessageItem.kt`
*   `mail/src/main/java/net/melisma/mail/ui/threaddetail/ThreadDetailUIState.kt` (read, structure understood)

## 2. Objectives and Plans

**Initial Objective (REQ-CACHE-003):**
*   Implement selective offline download for message bodies and attachments based on user preferences (`ALWAYS`, `ON_WIFI`, `ON_DEMAND`).

**Initial Misinterpretation & Correction:**
*   Initially, the `ON_DEMAND` preference was misinterpreted as requiring manual download buttons in the UI (`MessageDetailScreen`, `FullMessageDisplayUnit`).
*   **Correction**: The requirement was clarified: downloads should be *automatic* based on user preferences and network state, both during sync and when a message is actively viewed. Manual buttons were not required. A key clarification was that if a user opens a message and *any* network is available, content should download even if the preference is `ON_WIFI` (treating active view as a form of "on demand").

**Architectural Decision:**
*   The "ViewModel Provides 'Actionability' State" approach was adopted. ViewModels would determine richer `ContentDisplayState` (e.g., `DOWNLOADED`, `NOT_DOWNLOADED_WAITING_FOR_WIFI`, `LOADING`) for the UI, simplifying UI logic.

**High-Level Plan for REQ-CACHE-003 (Revised):**
1.  **Data Layer & Preferences**: Add `DownloadPreference` enum, update `UserPreferences`, `UserPreferencesRepository`.
2.  **UI Layer - Settings**: Allow users to configure download preferences.
3.  **Worker Modifications**: Update `MessageBodyDownloadWorker` and `AttachmentDownloadWorker` to:
    *   Respect download preferences and network state.
    *   Accept necessary input data (account ID, message ID, attachment ID).
    *   Provide clear output data for success (e.g., body content, attachment URI) or failure (error message) for ViewModels to observe.
4.  **Sync Worker**: Modify `FolderContentSyncWorker` to enqueue body/attachment downloads for newly fetched messages based on preferences and network.
5.  **Message View (Detail)**: Refactor `MessageDetailViewModel` and `MessageDetailScreen`:
    *   Inject `NetworkMonitor`.
    *   Define and use `ContentDisplayState`.
    *   Automatically trigger downloads when a message is viewed if content is missing and network/preferences allow (active view rule).
    *   Observe `WorkManager` for download status.
    *   Display status messages instead of download buttons (except retry on error).
6.  **Message View (Thread)**: Refactor `ThreadDetailViewModel` and `FullMessageDisplayUnit` similarly:
    *   Define `BodyLoadingState` (analogous to `ContentDisplayState`).
    *   Implement automatic download logic for messages within a thread.

**Subsequent Objective: Fix Build Errors**
*   After implementing the above, the primary objective shifted to resolving compilation errors and lint issues to achieve a successful build.

## 3. Requirement Sources

*   **`OFFLINE2.MD`**: Primarily **REQ-CACHE-003: Selective Offline Download (Attachments/Bodies)**.
*   **User Interaction/Clarification**:
    *   Correction of the initial misinterpretation about manual download buttons.
    *   The critical "download if online (any connection) when actively viewed" rule, even if the preference is `ON_WIFI`. This refined the understanding of "on demand" for active views.

## 4. Accomplishments

*   **Preference System**:
    *   `DownloadPreference` enum (`ALWAYS`, `ON_WIFI`, `ON_DEMAND`) created.
    *   `UserPreferences` data class and `UserPreferencesRepository` updated to store and expose these preferences.
    *   Settings UI (`SettingsScreen.kt`, `MainViewModel.kt`) updated to allow user configuration of download preferences; new string resources added.
*   **ViewModel & UI Refactoring (for automatic downloads and state display):**
    *   `MessageDetailViewModel.kt`:
        *   Injected `NetworkMonitor`.
        *   `MessageDetailScreenState` created with `ContentDisplayState` for body and attachments.
        *   Logic implemented to evaluate and trigger downloads on view, respecting preferences and the "active view" rule.
        *   Observation of `WorkManager` for download progress/status.
    *   `MessageDetailScreen.kt`:
        *   Updated to consume the new `MessageDetailScreenState`.
        *   Display logic changed to show status messages (e.g., "Message body will download automatically on Wi-Fi.") instead of manual download buttons.
        *   New string resources added for status messages.
    *   `BodyLoadingState.kt` (for threads): Created, similar to `ContentDisplayState`.
    *   `ThreadMessageItem.kt`: Updated to use `BodyLoadingState.Initial`.
    *   `ThreadDetailViewModel.kt`:
        *   Injected `NetworkMonitor`.
        *   Refactored to manage `BodyLoadingState` for each message in a thread.
        *   Logic for automatic download of message bodies within a thread based on active view rule, preferences, and network state.
        *   Observation of `WorkManager` for download status.
    *   `FullMessageDisplayUnit.kt`:
        *   Refactored to use the new `BodyLoadingState` from `ThreadMessageItem`.
        *   Removed direct preference/network parameters and explicit download buttons (except retry on error).
*   **Download Worker Enhancements**:
    *   `MessageBodyDownloadWorker.kt` & `AttachmentDownloadWorker.kt`:
        *   Updated to accept `ACCOUNT_ID`, `MESSAGE_ID` (and `ATTACHMENT_ID`/`NAME` for attachments) as input.
        *   Modified to return structured output data (`KEY_RESULT_BODY`/`KEY_RESULT_ATTACHMENT_URI` on success, `KEY_RESULT_ERROR` on failure).
        *   Refactored to use an injected `NetworkMonitor` instead of direct `ConnectivityManager` calls.
*   **Sync Process Enhancement**:
    *   `FolderContentSyncWorker.kt`:
        *   Updated to enqueue `MessageBodyDownloadWorker` and `AttachmentDownloadWorker` for newly synced messages based on user preferences and current network state (via `NetworkMonitor`).
*   **Core Connectivity**:
    *   `NetworkMonitor.kt` interface extended to include `isWifiConnected: Flow<Boolean>`.
    *   `AndroidNetworkMonitor.kt` implementation updated to provide this.

## 5. Shortcuts and Tech Debt

**Caused:**
*   **Broken Build**: The most significant issue is that the codebase is currently not building due to persistent compilation errors, primarily in `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailScreen.kt` and `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt`.

**Identified/Addressed during refactoring:**
*   **Direct `ConnectivityManager` Usage in Workers**: Initially, workers used `ConnectivityManager` directly. This was refactored to use the centralized `NetworkMonitor` from `:core-data`.
*   **Misunderstanding of `GetThreadDetailsUseCase`**: The initial assumption about `getThreadDetailsUseCase.getThreadById()` was incorrect. The ViewModel (`ThreadDetailViewModel`) was refactored to use the actual `invoke(threadId, currentState)` signature, integrating with `ThreadRepository.threadDataState`.
*   **Redundant `distinctUntilChanged()`**: Removed from a `StateFlow` in `ThreadDetailViewModel`.
*   **Duplicate `MessageDetailUIState`**: A duplicate definition in `MessageDetailViewModel.kt` was removed.

**Identified Tech Debt (Potential/Remaining):**
*   **Inconsistent Network Checking**: While workers were refactored to use `NetworkMonitor`, `MessageDetailViewModel` and `ThreadDetailViewModel` still use a local `wifiStatusFlow` based on direct `ConnectivityManager` usage for Wi-Fi checks. This should be consolidated to use `NetworkMonitor.isWifiConnected`.
*   **`GetThreadDetailsUseCase` Design**: The use case's reliance on filtering an existing `ThreadDataState` might not be robust for all navigation scenarios (e.g., deep linking to a thread not in a prefetched list). The current ViewModel adaptation works but the underlying use case design could be reviewed for such scenarios.
*   **Error Handling Granularity**: Error messages and their display could be made more user-friendly and specific across the UI. The current `transientError` mechanism is basic.
*   **`ContentDisplayState.ERROR`**: The `ContentDisplayState.ERROR` object in `MessageDetailViewModel` does not carry the error message itself; the message is in `transientError`. This separation is functional but was a point of confusion during debugging.
*   **String Resource Management**: Some string resources for UI states (e.g., attachment status in `AttachmentCard`) appear to be missing or misreferenced, contributing to build errors.

## 6. Build Error Debugging Steps Taken

A significant amount of time was spent trying to resolve build errors. Key debugging steps included:

1.  **`Unresolved reference: awaitClose`**:
    *   **Action**: Added `import kotlinx.coroutines.channels.awaitClose` to `MessageDetailViewModel.kt` and `ThreadDetailViewModel.kt`.
2.  **ViewModel ID Issues (`accountId`, `messageId` unresolved or type mismatch in `workDataOf`):**
    *   **Action**: Ensured ViewModel class properties `this.accountId` and `this.messageId` were used or correctly passed as parameters. For `workDataOf`, attempted to ensure these were explicitly non-null strings. This area seems to still have issues in `MessageDetailViewModel.kt`.
3.  **`@Composable invocations can only happen from the context of a @Composable function`**:
    *   **Action**: In `FullMessageDisplayUnit.kt`, moved `stringResource` calls out of `remember` calculation lambdas. Attempted similar for `MessageDetailScreen.kt`. One such error persists.
4.  **`GetThreadDetailsUseCase` Misuse**:
    *   **Action**: Read the use case, identified the `invoke(threadId, currentState)` signature. Refactored `ThreadDetailViewModel.kt` to inject `ThreadRepository`, combine `threadIdFlow` with `threadRepository.threadDataState`, and then call the use case.
5.  **Lint: `MissingPermission` for `ACCESS_NETWORK_STATE`**:
    *   **Action**: Refactored `MessageBodyDownloadWorker`, `AttachmentDownloadWorker`, and `FolderContentSyncWorker` to inject and use `NetworkMonitor` (from `:core-data`). Checked `core-data/src/main/AndroidManifest.xml` and confirmed the permission was already present.
6.  **`MessageDetailUIState` Redeclaration**:
    *   **Action**: Removed the duplicate definition from `MessageDetailViewModel.kt`.
7.  **`ContentDisplayState.ERROR` Misuse**:
    *   **Action**: Changed `ContentDisplayState.ERROR(errorMsg)` to `ContentDisplayState.ERROR` and moved the `errorMsg` to the `transientError` field in `MessageDetailScreenState` within `MessageDetailViewModel.kt`.
8.  **Persistent `MessageDetailScreen.kt` Errors**:
    *   **Problem**: `Unresolved reference 'message'` (and its properties like `subject`, `attachments`) and `Unresolved reference 'id'` on attachment objects, despite the `Message` and `Attachment` models appearing correct and attempts to clarify scope (e.g., `val currentMessage = overallState.message`).
    *   **Action**: Attempted to refactor by passing `overallMessageState.message` to a new internal composable (this was the last attempt before this summary).
9.  **Missing String Resources in `MessageDetailScreen.kt` (for `AttachmentCard`)**:
    *   **Problem**: Errors like `Unresolved reference 'attachment_waiting_wifi'`.
    *   **Action**: Not yet explicitly addressed by adding these to `strings.xml` in the last few cycles, as other errors were more pressing.

The build remains broken, with the compiler struggling with type inference and reference resolution in `MessageDetailScreen.kt` and parts of `MessageDetailViewModel.kt`. 