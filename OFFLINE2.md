# **Offline-First Architecture Migration Plan for Melisma Mail**

## **1\. Vision & Objectives**

**Vision:** Melisma Mail will provide a seamless, responsive, and reliable user experience, allowing
users to access and interact with their emails even without an active internet connection. Core
actions (reading, composing, replying, organizing) will be instantly available, with data
synchronization occurring transparently in the background. The application will intelligently manage
local data to provide a rich offline experience while respecting device storage and battery life.  
**Objectives:**

1. **Always Available:** Users can read, browse, and organize a significant corpus of previously
   synced emails, threads, bodies, and attachments offline, based on defined data caching policies.
2. **Seamless Actions:** Users can perform actions (compose, reply, delete, mark read/unread, move,
   star) offline. These actions are queued locally and synced reliably when connectivity is
   restored.
3. **Responsive UI:** The UI is always fast, reading data directly from the local database. Network
   operations happen in the background and do not block user interactions.
4. **Transparent Sync & Error Handling:** Users have a clear understanding of the app's sync status,
   when data was last updated, and if any actions are pending, successful, or have failed, with
   clear pathways for resolution.
5. **Data Integrity & Reliability:** Ensure that offline actions are eventually consistent with the
   server and that local data accurately reflects the synchronized state, including server-side
   deletions.
6. **Battery Efficient:** Background synchronization is optimized to be battery-friendly using
   WorkManager and intelligent sync triggers.
7. **Scalable & Maintainability:** The new architecture should be easier to maintain and extend with
   new features.
8. **Intelligent Data Management:** Implement clear policies for initial data synchronization, cache
   eviction, and attachment handling to balance offline availability with resource constraints. This includes user-configurable cache size limits and accurate accounting of stored data (message bodies, attachments).

## **2\. Current State, Technical Debt & Key Assumptions (Updated by AI Assistant - July 2025)**

**Current Implemented State (Summary):**

* **Project Build Status: SUCCESSFUL.** The entire project, including all modules (`:mail`, `:data`,
  `:domain`, `:core-data`, `:core-db`, `:backend-microsoft`, `:backend-google`), now compiles
  successfully.

* **Phase 0 & 1.A (Foundation & Build Stabilization): COMPLETED.**
    * **All previously documented build blockers have been resolved.** This includes:
        * Fixing various compilation errors in `:data`, `:core-db`, and `:mail` modules.
        * Resolving KSP Hilt `CoroutineDispatcher` and Room Foreign Key errors.
        * Correcting unresolved references (`ACTION_STAR_MESSAGE`, `username`).
        * Adding missing method overrides to interfaces (`observeMessageAttachments`, `getLocalFolderUuidByRemoteId`).
        * Standardizing payload keys and action names between `DefaultMessageRepository` and
          `ActionUploadWorker`.
    * **Investigation of former "showstoppers" from the previous report:**
        * **`GmailApiHelper.kt`:** This file now compiles. An in-depth review revealed that the
          `Message.fromApi` factory function and the `Attachment` constructor were being used
          correctly. The previous report's concern about outdated constructors was unfounded or
          based on an intermediate state that has since been fixed.
        * **`MessageMappers.kt`:** The "anomalous compiler errors" are no longer present. The file
          compiles successfully, and the mapping logic appears correct. This was likely a phantom
          error caused by upstream dependency issues that are now resolved.
        * **`ActionUploadWorker.kt`:** The `markMessageAsRead` call is present and functional. A
          minor code quality issue regarding mixed `Boolean` and `Result` types was identified and
          refactored for clarity and type safety.

* **Phase 1.B (SyncEngine Orchestration & Core Sync Enhancement): LARGELY COMPLETED.**
    * `SyncEngine.kt` has been implemented. It orchestrates `FolderListSyncWorker` and, upon
      its success, triggers `FolderContentSyncWorker` for key folders ("INBOX", "DRAFTS", "SENT").
      This addresses the critical need for ordered synchronization.
    * `FolderListSyncWorker.kt` and `FolderContentSyncWorker.kt` have been enhanced.
      `FolderContentSyncWorker` now includes logic to attempt delta sync if a token is present,
      falling back to paged sync. It also primes `RemoteKeyEntity` for `MessageRemoteMediator`.
      The previous "basic delta sync check" (time-based) in `FolderContentSyncWorker` was removed
      in favor of the more robust token-based delta approach.

* **Phase 1.C (On-Demand and Action-Based Workers): COMPLETED.**
    * `MessageBodyDownloadWorker.kt` was finalized, now checks if content is already synced
      before downloading, and accurately calculates and stores the `sizeInBytes` of the downloaded body in `MessageBodyEntity`.
    * `ActionUploadWorker.kt` was finalized. Build issues were resolved (KSP, type ambiguities related to `kotlin.Result` vs. `androidx.work.ListenableWorker.Result`, and incorrect DAO method calls),
      and it now robustly handles local database updates after successful API calls for mark
      read/unread, star, move, and send actions. Draft creation/update logic was also refined to
      use server responses for local DB updates.

* **Phase 2.1 (Delta Sync for Server-Side Deletions - Foundation & Implementation): COMPLETED.**
    * `DeltaSyncResult.kt` data class created.
    * `MailApiService.kt` interface updated with `syncFolders(...)` and `syncMessagesForFolder(...)`
      methods designed for delta synchronization.
    * **`GmailApiHelper.kt` and `GraphApiHelper.kt` now have fully implemented delta sync logic
      for `syncFolders` and `syncMessagesForFolder`, utilizing Gmail's `historyId` and
      Microsoft Graph's `deltaToken` respectively.**
    * `AccountEntity.kt` updated with `folderListSyncToken`.
    * `FolderEntity.kt` refactored: `id` is now always a local UUID, `type: String?` removed,
      `wellKnownType: WellKnownFolderType?` added, `messageListSyncToken` field added. An
      `@Index(value = ["accountId", "remoteId"], unique = true)` was added to prevent duplicate
      local folders for the same remote folder.
    * `AppDatabase.kt` version incremented (currently 15, reflecting addition of `sizeInBytes` to `MessageBodyEntity`). The `DatabaseModule.kt`
      now uses `.fallbackToDestructiveMigrationFrom(true, 1, ..., 14)`
      addressing crashes due to schema changes on development builds.
    * `FolderDao.kt` refactored: `insertOrUpdateFolders` now uses a `@Transaction` and manually
      checks for existing folders by `accountId` and `remoteId` to update them in place, preserving
      the local `id` and respecting the unique constraint. `getFolderByWellKnownType` and other
      methods updated.
    * `AccountDao.kt`, and `MessageDao.kt` updated with methods to update relevant
      tokens and to delete entities by a list of remote IDs.
    * `FolderMappers.kt` updated for new `FolderEntity` structure. `FolderEntity.toDomainModel()` now correctly maps the local UUID `FolderEntity.id` to `MailFolder.id`, crucial for UI stability.
    * `FolderListSyncWorker.kt` updated to always use UUID for `FolderEntity.id`, correctly map
      `wellKnownType`, and to perform a full folder refresh, deleting local folders not present
      in the server response to handle stale data. It also processes `deltaData.deletedItemIds`.
    * `SyncEngine.kt` updated to use `workManager.enqueueUniqueWork` with
      `ExistingWorkPolicy.KEEP` for `FolderListSyncWorker` to prevent redundant sync runs. The diagnostic log in `syncFolderContent` was removed as obsolete. It also now schedules `CacheCleanupWorker` periodically.
    * `FolderContentSyncWorker.kt` refactored to utilize these new delta sync structures,
      including processing deleted item IDs from `DeltaSyncResult`.
    * UI layer (`Util.kt`, `MailDrawerContent.kt`, `UtilTest.kt`) updated for `WellKnownFolderType`
      based icon logic.

* **Architectural Refinement (Well-Known Folders): COMPLETED.**
    * Implemented a robust strategy for handling well-known folders ("Inbox", "Sent", etc.).
    * `FolderEntity.id` (local PK) is now always a locally generated UUID.
    * `FolderEntity.wellKnownType: WellKnownFolderType` (enum) identifies functional folder types.
    * `FolderDao` uses `wellKnownType` for lookups.
    * All layers (Data, UI) consistently use this new approach, enhancing provider-agnosticism
      and data integrity for folder identification.

* **UI Stability Fixes (Message Loading & Settings): COMPLETED.**
    * **Message Display:** Resolved an issue where messages wouldn't load and "Parent folder not found" errors occurred.
    * **Settings Screen Buttons (Google Sign-In Cancellation):** Addressed an issue where "Add Account" buttons could remain disabled after cancelling a Google sign-in attempt.

* **Phase 4.A (Cache Configuration & Accurate Sizing Foundation): COMPLETED.**
    * `UserPreferencesRepository` updated to manage user-configurable cache size limits (options: 500MB, 1GB, 2GB, 5GB; default: 500MB).
    * `MessageBodyEntity` now includes a `sizeInBytes` field.
    * `MessageBodyDownloadWorker` calculates and stores `sizeInBytes` for fetched message bodies.
    * `CacheCleanupWorker` now:
        * Reads the cache size limit from `UserPreferencesRepository`.
        * Accurately calculates current cache size by summing `AttachmentEntity.size` and `MessageBodyEntity.sizeInBytes`.
        * Correctly subtracts `MessageBodyEntity.sizeInBytes` when evicting a message body.
    * `SettingsScreen.kt` in the `:mail` module now provides UI for users to select their preferred cache size limit.
    * `AppDatabase` version incremented to 15 to reflect schema changes in `MessageBodyEntity`.

* **Phase 4.B (Initial Sync Configuration): COMPLETED.**
    * **REQ-INIT-001: Configurable Initial Sync Duration:** Users can now select how far back emails are synced when an account is first added (options: 30 Days, 90 Days (default), 6 Months, All Time).
        * `UserPreferencesRepository` updated to store `InitialSyncDurationPreference`.
        * `FolderContentSyncWorker` now reads this preference and applies it by passing an `earliestTimestampEpochMillis` to the `MailApiService.getMessagesForFolder` method.
        * `GmailApiHelper` and `GraphApiHelper` updated to use this timestamp to filter messages via API query parameters (`q=after:` for Gmail, `$filter=receivedDateTime ge` for Graph) on the initial fetch for a folder.
        * `SettingsScreen.kt` in the `:mail` module now provides UI for users to select their preferred initial sync duration.

* **Phase 4.C (Selective Offline Download & ViewModel Refactoring - REQ-CACHE-003 Foundation): COMPLETED.**
    * **Preference System:** `DownloadPreference` enum (`ALWAYS`, `ON_WIFI`, `ON_DEMAND`) created. `UserPreferences` data class and `UserPreferencesRepository` updated to store and expose these preferences. Settings UI (`SettingsScreen.kt`, `MainViewModel.kt`) updated to allow user configuration.
    * **ViewModel & UI Refactoring:**
        * `MessageDetailViewModel.kt` & `ThreadDetailViewModel.kt`: Injected `NetworkMonitor` (and removed direct `ConnectivityManager` usage, resolving previous tech debt). Implemented logic to evaluate download preferences and network state (including `isWifiConnected` from `NetworkMonitor`) to automatically trigger downloads for message bodies and attachments when actively viewed, based on the "active view implies demand" rule. Manages `ContentDisplayState` and `BodyLoadingState` for richer UI feedback.
        * `MessageDetailScreen.kt` & `FullMessageDisplayUnit.kt` (in `:mail` module): Updated to consume new UI states, displaying status messages (e.g., "Message body will download automatically on Wi-Fi.") instead of manual download buttons (except for retry on error). String resource issues resolved.
        * `MessageBodyDownloadWorker.kt` & `AttachmentDownloadWorker.kt` updated to accept necessary input data (account ID, message ID, attachment ID/name), return structured output for success/failure, and use injected `NetworkMonitor`.
        * `FolderContentSyncWorker.kt` updated to enqueue `MessageBodyDownloadWorker` and `AttachmentDownloadWorker` for newly synced messages based on user preferences and current network state.
        * `NetworkMonitor.kt` interface extended to include `isWifiConnected: Flow<Boolean>`; `AndroidNetworkMonitor.kt` implementation updated.
        * All related build errors in `MessageDetailViewModel.kt` and `MessageDetailScreen.kt` (including Hilt `ConnectivityManager` injection, unresolved references, type mismatches, and string resource issues) have been resolved.

**Key Technical Debt & Immediate Concerns (Revised):**

1. **Duplicate Folder Issue & Resolution (COMPLETED):**
    * **Problem:** Folders were appearing duplicated in the UI. This was caused by
      `FolderListSyncWorker`
      being enqueued multiple times on startup and `FolderEntity` using a local UUID as its primary
      key without a server-side unique constraint, allowing multiple local entities for the same
      server folder.
    * **Impact:** Inconsistent UI, potential for incorrect data association, and violation of data
      integrity.
    * **Resolution:**
        * `SyncEngine` was modified to use `workManager.enqueueUniqueWork` with
          `ExistingWorkPolicy.KEEP`
          for `FolderListSyncWorker`, ensuring it only runs once per account even if rapidly
          enqueued.
        * `FolderEntity` had a unique index
          `(@Index(value = ["accountId", "remoteId"], unique = true))`
          added to enforce that only one local folder entity can exist for a given server folder
          within an account.
        * `FolderDao.insertOrUpdateFolders` was refactored into a `@Transaction` method. It now
          queries for an existing folder by `accountId` and `remoteId`. If one exists, the existing
          entity is updated (preserving its local UUID `id`). If not, the new folder is inserted.
        * `FolderListSyncWorker` was updated to always fetch the full list of folders from the API
          (passing `null` sync token) and then remove any local folders for that account that are
          not present in the API's response, ensuring stale local folders are deleted.

2. **Room Database Schema Migration Crash (ADDRESSED):**
    * **Problem:** The application crashed on initial startup after the unique index was added to
      `FolderEntity` due to a Room schema change without a specific migration path defined for
      that version increment. (This also applies to any unhandled schema change).
    * **Impact:** Users with existing installations on development/test builds would experience a
      crash, preventing app use.
    * **Resolution:** The `AppDatabase` version is currently 15. The project now uses
      `.fallbackToDestructiveMigrationFrom(true, 1, ..., 14)`
      in `DatabaseModule.kt`. This means that during development, Room will clear the database
      if a migration is missing and the schema is one of the specified versions (1-14),
      resolving the crash. For users with older development builds, an app uninstall and reinstall
      (or clearing app data) was the recommended solution to ensure the new schema is correctly applied.

3. **Pagination and `MessageRemoteMediator` Verification (COMPLETED):**
    * **Problem:** While `FolderContentSyncWorker` now primes `RemoteKeyEntity`, and `MessageRemoteMediator` now receives the correct folder UUID, it remained to be thoroughly confirmed if it correctly loads and displays *all* pages of messages when a user scrolls, especially after delta sync operations might reset pagination.
    * **Impact:** Users may not be able to scroll through their full message history seamlessly.
    * **Resolution (June 2025 - Phase 3):**
        * `MessageRemoteMediator.initialize()` was changed to return `SKIP_INITIAL_REFRESH`.
        * `MessageRemoteMediator.load()` for `LoadType.REFRESH` was modified to be a no-op (return `MediatorResult.Success(endOfPaginationReached = true)`) without clearing the database or fetching network data. This allows `FolderContentSyncWorker` to be the sole authority for data freshness and initial content loading for a folder.
        * The `LoadType.APPEND` logic for fetching older messages (scrolling down) was preserved.
    * **Status: COMPLETED.**

4. **`SyncEngine` Robustness for `FolderContentSyncWorker` (Medium Priority - Diagnostic Added):**
    * **Problem:** Logs indicated that `SyncEngine` sometimes enqueues `FolderContentSyncWorker` with the folder's `remoteId` as the `"FOLDER_ID"` input instead of the local UUID, causing those worker instances to fail. This was likely due to concurrency or how multiple calls were handled after `FolderListSyncWorker` success. A diagnostic log was added to `SyncEngine.syncFolderContent` to detect occurrences.
    * **Impact:** Inconsistent background sync for folder contents.
    * **Resolution (June 2025 - Investigation & Cleanup):**
        * **Investigation:** A thorough review of `SyncEngine.kt`, `FolderListSyncWorker.kt`, `FolderDao.kt`, `FolderEntity.kt`, and `FolderMappers.kt` was conducted.
        * **Findings:** The current implementation of `FolderListSyncWorker` correctly generates a new local UUID for each `FolderEntity.id`. The `FolderMappers.toEntity()` function correctly assigns this UUID to `FolderEntity.id`. The `FolderDao.insertOrUpdateFolders()` method preserves existing local UUIDs when updating folders. `SyncEngine.syncFolders()` correctly retrieves the `FolderEntity` (with its local UUID `id`) using `folderDao.getFolderByWellKnownTypeSuspend()` and then passes this `folder.id` to `syncFolderContent()`.
        * **Conclusion:** The architectural changes ensuring `FolderEntity.id` is always a local UUID and is correctly propagated have made the specific concern of passing a `remoteId` as the local `FOLDER_ID` highly unlikely. The diagnostic log added previously is now considered obsolete.
        * **Action:** The diagnostic logging block within `SyncEngine.syncFolderContent()` has been removed.
    * **Status: ADDRESSED / OBSOLETE.** Further monitoring for any new, unexpected behavior in `SyncEngine`'s worker enqueuing remains part of general sync robustness checks.

2.  **`ContentDisplayState.ERROR` in `MessageDetailViewModel` (Minor Observation):**
    *   **Problem:** The `ContentDisplayState.ERROR` object itself does not carry the specific error message; the message is typically handled via a separate `transientError` field in the `MessageDetailScreenState`.
    *   **Impact:** This separation is functional but was noted as a point of minor confusion during previous debugging. No immediate functional impact, but could be harmonized in future UI state refactoring if desired.
    *   **Status: OBSERVED.** Current implementation is functional.

**Original Key Assumptions (Re-evaluated):**

* The existing Room database schema is largely suitable. `FolderEntity.id` (local PK) now **always
  uses a locally generated UUID**. The previous strategy of using well-known remote IDs (e.g., "
  SENT") for Gmail standard folder PKs has been **superseded by the UUID approach combined with a
  dedicated `wellKnownType: WellKnownFolderType` field** for functional identification. `MailFolder.id` in the domain layer now also correctly reflects this local UUID. **(UPDATED &
  VALID)**
* WorkManager is the chosen framework. **(VALID)**
* Existing MailApiService implementations are functional (for non-delta operations). **(VALID)**
* UI will be made fully reactive. **(ASSUMED, NEEDS VERIFICATION)**
* Server is the ultimate source of truth. **(VALID)**
* Server APIs provide mechanisms for delta sync. **(IMPLEMENTED)** This is
  critical, and API helper work is the next step.

## **3\. Phased Implementation Plan (Revised & Detailed)**

With the build stabilized and critical crash mitigated, the plan focuses on robust synchronization and intelligent data management.

---

**Sprint/Phase 1.A: Fix Build & Stabilize Data Layer**

* **Status: COMPLETED.** The project is in a buildable and runnable state.

**Sprint/Phase 1.A.2: Mitigate Message Sync Crashes (June 2025)**

* **Status: COMPLETED.**
* **Objective:** Prevent `SQLiteConstraintException` during message sync. Address "Parent folder not found" errors.
* **Tasks:**
    * Added defensive checks in `MessageRemoteMediator` to ensure `FolderEntity` exists locally
      before message load.
    * Added similar checks in `FolderContentSyncWorker`.
* **Outcome:** App is more stable, but underlying sync orchestration is still required for data to
  appear correctly.

---

**Sprint/Phase 1.B (Focus on Orchestration & Core Sync): Implement `SyncEngine`**

**Objective:** Establish reliable, ordered synchronization for accounts and folders.

* **Task 1.B.1: Design and Implement `SyncEngine.kt` (Initial Version)**
    * **Status: COMPLETED.**
    * **Details:** `SyncEngine` implemented to manage `FolderListSyncWorker` and trigger
      `FolderContentSyncWorker` for key folders (INBOX, DRAFTS, SENT) on success. DAO method
      (`FolderDao.getFolderByAccountIdAndRemoteId`) added and used.

* **Task 1.B.2: Verify `MessageRemoteMediator` and Pagination (Post `SyncEngine` folder readiness)**
    * **Status: PENDING VERIFICATION (QA Focus).**
    * **Details:** `FolderContentSyncWorker` now updates `RemoteKeyEntity` to prime
      `MessageRemoteMediator`. Core code implementation appears complete as per Task 3.1. This task now primarily represents thorough QA testing of pagination under various sync conditions.

* **Task 1.B.3: Enhance `FolderListSyncWorker.kt` & `FolderContentSyncWorker.kt`**
    * **Status: SIGNIFICANTLY ADDRESSED.**
    * **Details:** Workers enhanced for initial sync. `FolderContentSyncWorker` now uses
      `messageListSyncToken` to decide between delta or paged sync, and for paged sync, it respects the user-configured initial sync duration. Foundational support for
      delta sync (tokens, DAO methods for deletions) added.

---

**Sprint/Phase 1.C: Implement On-Demand and Action-Based Workers (Reliably)**

**Objective:** Allow on-demand download of full message bodies and enable a more robust offline
action queue, orchestrated by `SyncEngine`.

* **Task 1.C.1: Finalize `MessageBodyDownloadWorker.kt`**
    * **Status: COMPLETED.**
    * **Details:** Worker logic enhanced to check if the message body is already synced before
      attempting download.

* **Task 1.C.2: Finalize `ActionUploadWorker.kt`**
    * **Status: COMPLETED.**
    * **Details:** Resolved KSP build errors and type ambiguities. Implemented local database
      updates
      for mark read/unread, star, move, and send actions. Draft creation/update also updates local
      DB.

---

**Sprint/Phase 2: Advanced Sync Features & Offline Queue**

**Objective:** Implement comprehensive delta sync (including deletions) and refine the offline
action queue.

* **Task 2.1: Implement Delta Sync for Server-Side Deletions**
    * **Status: COMPLETED.**
    * **Details:**
        * Created `DeltaSyncResult.kt`.
        * Added `syncFolders` and `syncMessagesForFolder` to `MailApiService`.
        * **Implemented delta sync logic (Gmail `historyId`, MS Graph `deltaToken`) in
          `GmailApiHelper.kt` and `GraphApiHelper.kt` for both folder and message synchronization.**
        * Added `folderListSyncToken` to `AccountEntity`, `messageListSyncToken` and unique index
          (`accountId`, `remoteId`) to `FolderEntity`.
        * Incremented `AppDatabase` version to 15, using `fallbackToDestructiveMigration`.
        * Added DAO methods for token updates, batch deletions (`deleteFoldersByRemoteIds`,
          `deleteMessagesByRemoteIds`), and refactored `FolderDao.insertOrUpdateFolders` for
          conflict resolution based on `accountId` and `remoteId`.
        * Refactored `FolderListSyncWorker` for full folder refresh and stale data removal, and to
          use `enqueueUniqueWork` via `SyncEngine`.
        * Refactored `FolderContentSyncWorker` to use new delta sync methods and process
          `DeltaSyncResult` (including deleted IDs).
    * **Outcome:** Core delta synchronization capabilities are now implemented across the stack,
      from
      API helpers through the data layer and into the sync workers, including handling of
      server-side deletions and prevention of duplicate local data.

* **Task 2.2: Refine Offline Action Queueing Mechanism**
    * **Status: COMPLETED.**
    * **Objective:** Define and implement the database table and DAOs for storing pending offline actions if not already robust. Refactor repositories to write to this queue.
    * **DoD:** Offline actions use a persistent queue read by `ActionUploadWorker`.
    * **Details:**
        * Created `PendingActionEntity` to store action details (type, payload, status, retries) in the database.
        * Created `PendingActionDao` for DB operations on the queue.
        * Updated `AppDatabase` (incremented to version 15) and `DatabaseModule` with the new components.
        * Refactored `SyncEngine` to replace direct worker enqueuing with a `queueAction` method that saves the action to the DB and triggers a single unique `ActionUploadWorker`.
        * Removed network checks from repositories and `SyncEngine`'s queuing logic, ensuring actions are *always* saved offline.
        * Refactored `DefaultMessageRepository` and `DefaultThreadRepository` to use `SyncEngine.queueAction`.
        * Re-architected `ActionUploadWorker` to be stateless. It now fetches the next available action from the `PendingActionDao`, processes it, and handles its lifecycle (success/delete, retry, or fail) within a loop. It also includes a network check before processing.
        * Added handling for thread-level actions (mark read, delete) to the worker.

---

**Sprint/Phase 3: Verify and Refactor Message Pagination (Post Delta Sync)**

**Objective:** Ensure `MessageRemoteMediator` works harmoniously with the delta-sync-first architecture, reliably handling pagination for older messages without interfering with `FolderContentSyncWorker`.

* **Task 3.1: Verify `MessageRemoteMediator` and Pagination Behavior**
    * **Status: COMPLETED.**
    * **Problem:** The existing `MessageRemoteMediator` was designed before the robust `FolderContentSyncWorker` and its delta sync capabilities. Its `LoadType.REFRESH` behavior was destructive (clearing local data) and could conflict with data synced by the worker, leading to inefficiencies and potential data loss or UI flickering.
    * **Investigation:** Reviewed `MessageRemoteMediator.kt`, `FolderContentSyncWorker.kt`, `RemoteKeyEntity.kt`, and `RemoteKeyDao.kt`. Confirmed that `FolderContentSyncWorker` correctly handles both initial paged syncs and subsequent delta syncs, including resetting pagination tokens when delta updates occur. The mediator's aggressive refresh was redundant and harmful.
    * **Resolution:**
        * Modified `MessageRemoteMediator.initialize()` to return `InitializeAction.SKIP_INITIAL_REFRESH`. This prevents the mediator from running its own sync cycle when the UI first loads, relying instead on `SyncEngine` and `FolderContentSyncWorker` to ensure data is present.
        * Modified the `load()` method for `LoadType.REFRESH`:
            * It no longer clears the local database for the folder.
            * It no longer makes a network call to fetch messages.
            * It now immediately returns `MediatorResult.Success`, with `endOfPaginationReached` determined by checking the existing `RemoteKeyEntity` for a `nextPageToken`. This effectively makes the mediator's refresh a no-op concerning data fetching, as `FolderContentSyncWorker` is responsible for updating the newest data. A UI pull-to-refresh should trigger `FolderContentSyncWorker` externally.
        * The `LoadType.APPEND` logic in the mediator remains unchanged, allowing it to continue fetching older pages of messages when the user scrolls down.
    * **Outcome:** `MessageRemoteMediator` now correctly defers to `FolderContentSyncWorker` for syncing new/updated messages and only handles the loading of older message pages on demand. This resolves the conflict, improves efficiency, and ensures a more stable pagination experience.

---

**Sprint/Phase 4: Intelligent Cache Management & UI Polish (Current Focus)**

**Objective:** Implement sophisticated cache management policies, provide user controls for caching, and enhance UI feedback for sync and data states.

* **REQ-CACHE-001: Configurable Cache Size Limit**
    * **Task 4.A.1: Data Layer Support for Cache Configuration.**
        * **Status: COMPLETED.**
        * **Details:**
            * `UserPreferencesRepository` updated with `CacheSizePreference` enum (500MB, 1GB, 2GB, 5GB) and methods to store/retrieve the limit (in bytes). Default set to 500MB.
            * `MessageBodyEntity` updated with `sizeInBytes: Long` field.
            * `MessageBodyDownloadWorker` updated to calculate and store `sizeInBytes` of fetched body content.
            * `AppDatabase` version incremented to 15; `DatabaseModule` updated with destructive migration from version 14.
    * **Task 4.A.2: `CacheCleanupWorker` Enhancements.**
        * **Status: COMPLETED.**
        * **Details:**
            * `CacheCleanupWorker` now injects `UserPreferencesRepository` and uses the configured `cacheSizeLimitBytes`.
            * `getCurrentCacheSizeBytes()` in `CacheCleanupWorker` now sums `AttachmentEntity.size` and `MessageBodyEntity.sizeInBytes` (via new `MessageBodyDao.getAllMessageBodies()`).
            * Eviction logic in `CacheCleanupWorker` correctly subtracts `MessageBodyEntity.sizeInBytes` when a body is deleted.
    * **Task 4.A.3: UI for Cache Configuration.**
        * **Status: COMPLETED.**
        * **Details:**
            * `MainScreenState` in `MainViewModel` updated to hold `currentCacheSizePreference` and `availableCacheSizes`.
            * `MainViewModel` updated to load and expose these preferences, and provide `setCacheSizePreference()` method.
            * `SettingsScreen.kt` now includes a UI section for users to select their desired cache size from the available options, displaying the current setting.
    * **Outcome:** Users can now configure their desired local cache storage limit. The `CacheCleanupWorker` respects this limit and more accurately calculates the current cache size by including message bodies.

* **REQ-CACHE-002: Advanced Cache Eviction Policy (Refined)**
    * **Status: COMPLETED.**
    * **Objective:** Implement the full eviction policy (exclusions, priorities) as defined in `ARCHITECTURE.MD`, using `lastAccessedTimestamp` and overall age.
    * **Details:**
        *   `PendingActionDao` updated with `getActiveActionEntityIds` to identify messages involved in active pending actions.
        *   `CacheCleanupWorker.doWork()` method significantly refactored:
            *   Injects `PendingActionDao`.
            *   Filters initial candidates by excluding messages linked to active `PendingActionEntity`s.
            *   Implements a two-tiered eviction strategy:
                1.  **Tier 1:** Evicts data (attachments, then bodies, then headers) for messages whose own `timestamp` is older than 90 days AND were not accessed in the last 90 days. Sorted by message `timestamp` (oldest first), then `lastAccessedTimestamp`.
                2.  **Tier 2 (if target cache size not met):** Evicts data (attachments, then bodies, then headers) for messages whose own `timestamp` is NOT older than 90 days BUT were not accessed in the last 90 days. Sorted by `lastAccessedTimestamp` (least recently accessed first), then by message `timestamp`.
            *   The `MessageDao.getCacheEvictionCandidates` query continues to provide the base list of candidates (not recently accessed, not draft/outbox, correct sync status). The worker then applies the more granular filtering and tiered sorting.
            *   Logging within the worker was improved to reflect the new tiered logic.
    * **Outcome:** `CacheCleanupWorker` now strictly adheres to the advanced multi-priority eviction strategy defined in `ARCHITECTURE.MD`, including exclusions for recently accessed items and items with pending actions. This ensures a more intelligent and policy-compliant cache cleanup process.
    * **Next Steps:** (Removed as this task is complete)

* **REQ-INIT-001: Configurable Initial Sync Duration**
    * **Status: COMPLETED.**
    * **Objective:** Allow users to choose the time window for initial full sync (e.g., 30, 60, 90 days).

* **REQ-CACHE-003: Selective Offline Download (Attachments/Bodies)**
    * **Status: IMPLEMENTATION COMPLETE, UI POLISHED.**
    * **Objective:** Allow users to configure if message bodies and/or attachments are downloaded automatically or only on demand, based on preferences and network state (including "active view implies demand" rule).
    * **Details:**
        * User preferences for body and attachment downloads (`ALWAYS`, `ON_WIFI`, `ON_DEMAND`) are now configurable via `SettingsScreen` and stored via `UserPreferencesRepository`.
        * `MessageDetailViewModel` and `ThreadDetailViewModel` have been refactored to:
            * Use `NetworkMonitor` (including `isWifiConnected`) instead of direct `ConnectivityManager`.
            * Automatically evaluate download needs for message bodies/attachments when a message is viewed, respecting preferences and network state.
            * Trigger `MessageBodyDownloadWorker` or `AttachmentDownloadWorker` as needed.
            * Expose detailed `ContentDisplayState` / `BodyLoadingState` to the UI.
            * Implement retry mechanisms for failed downloads, observable through `WorkInfo` updates.
        * `MessageDetailScreen` displays these states (e.g., "Downloading...", "Will download on Wi-Fi", "Error. Tap to retry.") and now uses `AnimatedContent` for smoother transitions between states. Retry functionality is exposed via clickable UI elements in error states.
        * `FolderContentSyncWorker` enqueues body/attachment downloads for newly synced messages according to preferences.
    * **Next Steps:** Thorough Quality Assurance (QA) testing of all preference combinations and network conditions. Evaluate if any edge cases in download logic need refinement.

* **REQ-SYNC-005: Auto-Refresh Message on View**
    * **Status: Pending (Needs robust implementation and integration).**
    * **Objective:** When a user opens a message, if online and data is potentially stale, silently refresh its content (body & attachments).
    * **Next Steps:** Integrate this refresh trigger into `MessageDetailViewModel` or repository layer, coordinated via `SyncEngine`.

---

## 4\. Next Steps & Current Build Status

### Current Build Status: SUCCESSFUL & STABLE (DB Version 15)

The project **builds and compiles successfully**. `SyncEngine` orchestrates initial folder list, key folder content sync, and schedules periodic cache cleanup. Delta synchronization for folders and messages, including handling of server-side deletions, is implemented in the API helpers and integrated into the sync workers. Duplicate folder issues have been resolved, and database schema changes are handled via `fallbackToDestructiveMigration` (current DB version 15). Workers for on-demand actions and body downloads are functional; `MessageBodyDownloadWorker` now records body sizes. The offline action queue is persistent and robust. Message pagination is verified. `UserPreferencesRepository` and `SettingsScreen` now support user-configurable cache size limits and initial sync durations. `CacheCleanupWorker` now uses these settings, more accurately tracks cache usage (including message body sizes), and implements the **refined advanced eviction policy (REQ-CACHE-002) including tiered eviction and exclusion of pending actions**. The `lastAccessedTimestamp` field in `MessageEntity` is updated when message details are viewed.
**The foundational logic for REQ-CACHE-003 (Selective Offline Download for Attachments/Bodies) is now implemented, including user preferences, ViewModel logic for automatic downloads based on active view and network state (using `NetworkMonitor.isWifiConnected` and removing direct `ConnectivityManager` usage), worker enhancements, and UI display of download states. All related build errors have been resolved. UI polish for these download states, including retry capabilities and smoother transitions using `AnimatedContent`, has also been completed.**

### Immediate Priorities:

1.  **Monitor & Refine Sync Robustness:**
    * **The Problem:** With significant changes to sync logic and new cache management, unknown edge cases or performance bottlenecks might exist.
    * **What Needs to Be Done:** Monitor application behavior, logs, and user feedback (if applicable) for any issues related to data consistency, sync frequency, cache eviction behavior, or performance.
    * **Next Step:** Ongoing observation during testing and development.
2.  **Quality Assurance for REQ-CACHE-003:**
    * **The Problem:** The newly implemented selective download logic (REQ-CACHE-003) requires thorough testing across various preference settings, network conditions, and user interaction flows.
    * **What Needs to Be Done:** Systematically test automatic downloads for message bodies and attachments in `MessageDetailScreen` and `ThreadDetailScreen` (via `FullMessageDisplayUnit`). Verify UI states and status messages.
    * **Next Step:** Dedicated QA cycle for REQ-CACHE-003.

* **API Helpers (`GmailApiHelper`, `GraphApiHelper`)**
    *   [x] Implement `syncFolders`
        * Gmail: Use labels list + current historyId as sync token. Worker to diff.
        * Graph: Use mailFolder delta query.
    *   [x] Implement `syncMessagesForFolder`
        * Gmail: Use messages history list.
        * Graph: Use message delta query for the folder.
* **`DeltaSyncResult<T>` (`core-data`)**
    *   [x] Created.
* **Database Entities & DAOs**
    *   [x] `AccountEntity` updated (`folderListSyncToken`).
    *   [x] `FolderEntity` updated (`messageListSyncToken`, local UUID PK, `wellKnownType`, unique index).
    *   [x] `MessageBodyEntity` updated (`sizeInBytes`).
    *   [x] DAOs updated for delta sync, token management, and `MessageBodyDao.getAllMessageBodies()`.
    *   [x] `AppDatabase` version updated to 15.
* **Sync Workers**
    *   [x] `FolderListSyncWorker` updated.
    *   [x] `FolderContentSyncWorker` updated.
    *   [x] `MessageBodyDownloadWorker` updated (stores body size).
    *   [x] `CacheCleanupWorker` updated (uses preferences, accurate sizing, advanced eviction policy REQ-CACHE-002).
* **User Preferences & UI**
    *   [x] `UserPreferencesRepository` updated for cache size limits.
    *   [x] `SettingsScreen` UI added for cache size configuration.
    *   [x] `MainViewModel` updated to manage cache size preference state.
    *   [x] `UserPreferencesRepository` updated for initial sync duration (`InitialSyncDurationPreference`).
    *   [x] `SettingsScreen` UI added for initial sync duration configuration.
    *   [x] `UserPreferencesRepository` updated for message body and attachment download preferences (`DownloadPreference`).
    *   [x] `SettingsScreen` UI added for body/attachment download preference configuration.
* **View Models & UI (Message/Thread Detail)**
    *   [x] Refactored `MessageDetailViewModel` & `ThreadDetailViewModel` to use `NetworkMonitor` (including `isWifiConnected`) and manage `ContentDisplayState`/`BodyLoadingState`.
    *   [x] Refactored `MessageDetailScreen` & `FullMessageDisplayUnit` to display download states and remove manual download buttons (except retry).
    *   [x] Resolved all related build errors.