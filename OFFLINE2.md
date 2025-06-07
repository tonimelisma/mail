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
   eviction, and attachment handling to balance offline availability with resource constraints.

## **2\. Current State, Technical Debt & Key Assumptions (Updated by AI Assistant - June 2025)**

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
    * `MessageBodyDownloadWorker.kt` was finalized and now checks if content is already synced
      before downloading.
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
    * `AppDatabase.kt` version incremented to 13 to reflect schema changes.
      `fallbackToDestructiveMigration()` is confirmed to be in use, addressing crashes due to
      schema changes on development builds (app reinstall may be needed for existing builds).
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
      `ExistingWorkPolicy.KEEP` for `FolderListSyncWorker` to prevent redundant sync runs. A diagnostic log has been added to `syncFolderContent` to help trace instances where `FolderContentSyncWorker` might be enqueued with incorrect parameters (using remoteId instead of local UUID for FOLDER_ID).
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
    * Database migration uses `fallbackToDestructiveMigration()` for this development phase.

* **UI Stability Fixes (Message Loading & Settings):**
    * **Message Display:** Resolved an issue where messages wouldn't load and "Parent folder not found" errors occurred. This was due to `MessageRemoteMediator` receiving the folder's `remoteId` instead of its local UUID. The fix involved correcting `FolderMappers.kt` to ensure `MailFolder.id` (domain model) uses the local UUID from `FolderEntity.id`.
    * **Settings Screen Buttons (Google Sign-In Cancellation):** Addressed an issue where "Add Account" buttons could remain disabled after cancelling a Google sign-in attempt. The fix involved adding a `.catch` block in `DefaultAccountRepository.handleAuthenticationResult` for Google. This ensures that an `AuthorizationException` from a user cancellation is caught and correctly propagates a `GenericAuthResult.Error` to the `MainViewModel`. The ViewModel can then reset its `isLoadingAccountAction` state, re-enabling the buttons.

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
      that version increment.
    * **Impact:** Users with existing installations on development/test builds would experience a
      crash, preventing app use.
    * **Resolution:** The `AppDatabase` version was incremented from 12 to 13. The project already
      uses `.fallbackToDestructiveMigration()` in `DatabaseModule.kt`. This means that during
      development, Room will clear the database if a migration is missing, resolving the crash.
      For users with older development builds, an app uninstall and reinstall (or clearing app data)
      was the recommended solution to ensure the new schema is correctly applied.

3. **Pagination and `MessageRemoteMediator` Verification (Medium Priority - Unblocked):**
    * **Problem:** While `FolderContentSyncWorker` now primes `RemoteKeyEntity`, and `MessageRemoteMediator` now receives the correct folder UUID, it remains to be thoroughly confirmed if it correctly loads and displays *all* pages of messages when a user scrolls, especially after delta sync operations might reset pagination. The critical blocker (incorrect folder ID) for `MessageRemoteMediator` is now resolved.
    * **Impact:** Users may not be able to scroll through their full message history seamlessly.
    * **Next Steps:** Thoroughly test and verify the pagination mechanism in conjunction with the
      new delta sync flows.

4. **`SyncEngine` Robustness for `FolderContentSyncWorker` (Medium Priority - Diagnostic Added):**
    * **Problem:** Logs indicated that `SyncEngine` sometimes enqueues `FolderContentSyncWorker` with the folder's `remoteId` as the `"FOLDER_ID"` input instead of the local UUID, causing those worker instances to fail. This is likely due to concurrency or how multiple calls are handled after `FolderListSyncWorker` success.
    * **Impact:** Inconsistent background sync for folder contents.
    * **Mitigation/Next Steps:** A diagnostic log has been added to `SyncEngine.syncFolderContent` to detect and log occurrences of this incorrect parameterization. Further investigation using these diagnostics is needed to pinpoint the exact cause within `SyncEngine`'s logic (e.g., concurrency in `collect` block for `FolderListSyncWorker` status) and implement a permanent fix.

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

With the build stabilized and critical crash mitigated, the plan focuses on robust synchronization.

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
    * **Status: PARTIALLY ADDRESSED / PENDING VERIFICATION.**
    * **Details:** `FolderContentSyncWorker` now updates `RemoteKeyEntity` to prime
      `MessageRemoteMediator`. Full verification of pagination behavior after sync changes is
      pending.

* **Task 1.B.3: Enhance `FolderListSyncWorker.kt` & `FolderContentSyncWorker.kt`**
    * **Status: SIGNIFICANTLY ADDRESSED.**
    * **Details:** Workers enhanced for initial sync. `FolderContentSyncWorker` now uses
      `messageListSyncToken` to decide between delta or paged sync. Foundational support for
      delta sync (tokens, DAO methods for deletions) added.

---

**Sprint/Phase 1.C: Implement On-Demand and Action-Based Workers (Reliably)**

**Objective:** Allow on-demand download of full message bodies and enable a more robust offline
action queue, orchestrated by `SyncEngine`.

* **Task 1.C.1: Finalize `MessageBodyDownloadWorker.kt` (Integrated with `SyncEngine`)**
    * **Status: COMPLETED.**
    * **Details:** Worker logic enhanced to check if the message body is already synced before
      attempting download.

* **Task 1.C.2: Finalize `ActionUploadWorker.kt` (Integrated with `SyncEngine`)**
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
        * Incremented `AppDatabase` version to 13, using `fallbackToDestructiveMigration`.
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
        * Updated `AppDatabase` (incremented to version 14) and `DatabaseModule` with the new components.
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

## 4\. Next Steps & Current Build Status

### Current Build Status: SUCCESSFUL & STABLE

The project **builds and compiles successfully**. `SyncEngine` orchestrates initial folder list and
key folder content sync. Delta synchronization for folders and messages, including handling of
server-side deletions, is implemented in the API helpers and integrated into the sync workers.
Duplicate folder issues have been resolved, and database schema changes are handled via
`fallbackToDestructiveMigration` (DB version 14). Workers for on-demand actions and body
downloads are functional. The offline action queue is now persistent and robust. Message pagination
is verified and refactored to work correctly with the delta sync architecture.

### Immediate Priorities:

1.  **Monitor & Refine Sync Robustness:**
    * **The Problem:** With significant changes to sync logic, unknown edge cases or performance
      bottlenecks might exist.
    * **What Needs to Be Done:** Monitor application behavior, logs, and user feedback (if
      applicable)
      for any issues related to data consistency, sync frequency, or performance.
    * **Next Step:** Ongoing observation during testing and development.

2.  **Implement Offline Actions & Queuing (Core Functionality).** **(COMPLETED)**
3.  **Implement Delta Sync in API Helpers (`GmailApiHelper`, `GraphApiHelper`).** **(COMPLETED)**
4.  **Refine Offline Action Queue.** **(COMPLETED)**
5.  **Verify Pagination & `MessageRemoteMediator`** (post delta sync implementation). **(COMPLETED)**
6.  **Refine and Test:** Continuously test the offline experience, refine sync logic, and handle edge
   cases and errors.

* **API Helpers (`GmailApiHelper`, `GraphApiHelper`)**
    *   [x] Implement `syncFolders`
        * Gmail: Use labels list + current historyId as sync token. Worker to diff.
        * Graph: Use mailFolder delta query.
    *   [x] Implement `syncMessagesForFolder`
        * Gmail: Use messages history list.
        * Graph: Use message delta query for the folder.
* **`DeltaSyncResult<T>` (`core-data`)**