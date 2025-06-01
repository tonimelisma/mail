# Offline-First Architecture Migration Plan for Melisma Mail

## 1. Vision & Objectives

**Vision:** Melisma Mail will provide a seamless, responsive, and reliable user experience, allowing
users to access and interact with their emails even without an active internet connection. Core
actions (reading, composing, replying, organizing) will be instantly available, with data
synchronization occurring transparently in the background.

**Objectives:**

1. **Always Available:** Users can read, browse, and organize all previously synced emails, threads,
   and attachments offline.
2. **Seamless Actions:** Users can perform actions (compose, reply, delete, mark read/unread, move,
   star) offline. These actions are queued locally and synced reliably when connectivity is
   restored.
3. **Responsive UI:** The UI is always fast, reading data directly from the local database. Network
   operations happen in the background and do not block user interactions.
4. **Transparent Sync:** Users have a clear understanding of the app's sync status, when data was
   last updated, and if any actions are pending or failed.
5. **Data Integrity & Reliability:** Ensure that offline actions are eventually consistent with the
   server and that local data accurately reflects the synchronized state.
6. **Battery Efficient:** Background synchronization is optimized to be battery-friendly using
   `WorkManager`.
7. **Scalability & Maintainability:** The new architecture should be easier to maintain and extend
   with new features.

## 2. Starting Point & Key Assumptions

**Current State:**

* The application has a modular structure (`:core-data`, `:core-db`, `:data`, `:domain`, `:mail`,
  backend modules).
* Room is used for local database persistence (`AppDatabase` with entities like `AccountEntity`,
  `FolderEntity`, `MessageEntity`, `MessageBodyEntity`, `AttachmentEntity`).
* Repositories (`DefaultAccountRepository`, `DefaultFolderRepository`, `DefaultMessageRepository`,
  `DefaultThreadRepository`) manage data flow, sometimes mixing local DB access with direct API
  calls for UI-bound data.
* `MailApiService` interface with `GmailApiHelper` and `GraphApiHelper` implementations handle
  backend communication.
* Hilt is used for dependency injection.
* `MessageRemoteMediator` is used for paginated message lists in `DefaultMessageRepository`, which
  is a good foundation.
* `SyncMessageStateWorker` exists for some offline actions in `DefaultMessageRepository`.
* A known bug exists where `MessageBodyEntity.content` might not persist correctly after network
  fetch and save.

**Key Assumptions:**

* The existing Room database schema is largely suitable but will require additions for sync
  metadata.
* `WorkManager` is the chosen framework for background synchronization tasks.
* The existing `MailApiService` implementations are functional for backend communication.
* UI will be made fully reactive to `Flow`s from Repositories.
* The server is the ultimate source of truth. Conflicts will generally be resolved by favoring
  server state, though optimistic updates will enhance UX.

**Known Unknowns/Risks (General):**

* **Full scope of `MessageBodyEntity` bug:** The exact root cause and all affected scenarios of the
  body persistence bug are not fully understood yet.
* **Complex Sync Logic:** Implementing robust delta sync, conflict resolution (especially for
  drafts), and handling various API error scenarios can be complex.
* **Data Volume & Storage Limits:** Strategies for managing large mailboxes and limiting local
  storage effectively will need careful design.
* **Migration Complexity:** Refactoring existing data flows in repositories and ViewModels requires
  careful planning to avoid regressions.
* **Battery Life Impact:** While `WorkManager` helps, poorly optimized sync strategies can still
  impact battery.

## 3. Phased Implementation Plan

### Phase 0: Foundation & Critical Bug Fixes

**Objective:** Stabilize the local database persistence and enrich entities with necessary metadata
for synchronization.

**Changes & Affected Files:**

1. **Stabilize `MessageBodyEntity` Persistence:**
    * **File(s):** `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`,
      `core-db/src/main/java/net/melisma/core_db/dao/MessageBodyDao.kt`,
      `core-db/src/main/java/net/melisma/core_db/dao/MessageDao.kt`.
    * **Changes:**
        * Thoroughly investigate and fix the bug in `DefaultMessageRepository.getMessageWithBody`
          ensuring `MessageBodyEntity.content` is reliably saved to and retrieved from
          `MessageBodyDao` after network fetches.
        * Verify transaction handling when saving `MessageEntity` and `MessageBodyEntity`.
    * **Assumptions:** The issue is likely within the transaction block or the interaction between
      DAO updates and Flow emissions.
    * **Risks:** If the bug is deeper than anticipated, it might delay subsequent phases.

2. **Enhance Room Entities with Sync Metadata:**
    * **File(s):**
        * `core-db/src/main/java/net/melisma/core_db/entity/AccountEntity.kt`
        * `core-db/src/main/java/net/melisma/core_db/entity/FolderEntity.kt`
        * `core-db/src/main/java/net/melisma/core_db/entity/MessageEntity.kt`
        * `core-db/src/main/java/net/melisma/core_db/entity/MessageBodyEntity.kt`
        * `core-db/src/main/java/net/melisma/core_db/entity/AttachmentEntity.kt`
        * `core-db/src/main/java/net/melisma/core_db/AppDatabase.kt` (for migrations)
    * **Changes:**
        * Define a common `SyncStatus` enum (e.g., `IDLE`, `SYNCED`, `PENDING_UPLOAD`,
          `PENDING_DOWNLOAD`, `ERROR`) probably in `:core-data:model`.
        * Add/Update fields in relevant entities:
            * `syncStatus: SyncStatus` (default to `IDLE` or `PENDING_DOWNLOAD` for new items from
              server).
            * `lastSyncAttemptTimestamp: Long?`
            * `lastSuccessfulSyncTimestamp: Long?`
            * `lastSyncError: String?`
            * `isLocalOnly: Boolean` (for items created offline).
            * `needsFullSync: Boolean` (flag for items needing a complete re-fetch).
        * Update existing fields like `needsSync` in `MessageEntity` to use the new `syncStatus`.
        * Implement necessary Room Migrations in `AppDatabase.kt`.
    * **Assumptions:** These fields will cover most sync state tracking needs.
    * **Risks:** Migration errors if not carefully implemented. Overlooking a necessary state in
      `SyncStatus`.

### Phase 1: Decoupling Read Path & Initial Sync Workers

**Objective:** Ensure all UI data reads from the local DB only, and introduce basic `WorkManager`
-based sync workers for primary data types.

**Changes & Affected Files:**

1. **Refactor Repository Read Methods (DB-Centric):**
    * **File(s):**
        * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
        * `data/src/main/java/net/melisma/data/repository/DefaultFolderRepository.kt`
        * `data/src/main/java/net/melisma/data/repository/DefaultThreadRepository.kt`
        * `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
    * **Changes:**
        * All public methods returning `Flow<Data>` for UI consumption (e.g., `getAccounts()`,
          `observeFoldersState()`, `getMessagesPager()`, `getMessageDetails()`,
          `observeMessagesForFolder()`, `threadDataState`'s backing flow) must *exclusively* query
          local DAOs.
        * Remove direct network calls from these read paths.
        * If data is missing or stale (based on new sync metadata), these methods should trigger a
          *non-blocking* request to the new `SyncEngine` (see below).
    * **Assumptions:** ViewModels can handle initial empty/loading states gracefully while data is
      synced in the background.
    * **Risks:** Breaking existing UI flows if ViewModels are not updated to correctly handle
      asynchronous loading and purely DB-backed flows. Performance issues if DB queries are not
      optimized.

2. **Create `SyncEngine` and Initial `SyncWorker`s:**
    * **File(s) (New & Existing):**
        * New: `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
        * New: `data/src/main/java/net/melisma/data/sync/workers/FolderListSyncWorker.kt`
        * New: `data/src/main/java/net/melisma/data/sync/workers/FolderContentSyncWorker.kt` (or
          adapt `MessageRemoteMediator` and `DefaultThreadRepository`'s fetch logic)
        * New: `data/src/main/java/net/melisma/data/sync/workers/MessageBodyDownloadWorker.kt`
        * New: `data/src/main/java/net/melisma/data/sync/workers/AttachmentDownloadWorker.kt`
        * `core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.kt` (used by
          workers)
        * Relevant DAOs (used by workers to save data)
        * `data/src/main/java/net/melisma/data/di/WorkManagerModule.kt` & other DI modules to
          provide `SyncEngine`.
    * **Changes:**
        * `SyncEngine`: A new class responsible for:
            * Receiving sync requests (e.g., `syncFolders(accountId)`,
              `syncFolderContent(accountId, folderId)`, `downloadMessageBody(messageId)`).
            * Enqueueing the appropriate `WorkManager` `SyncWorker`.
            * Possibly managing sync priorities, constraints (network, battery).
        * `SyncWorker`s:
            * Extend `CoroutineWorker`.
            * Take necessary parameters (e.g., `accountId`, `folderId`, `messageId`) via `Data`.
            * Use injected `MailApiService` (for the correct provider) to fetch data.
            * Use injected DAOs to save fetched data and update sync metadata (`syncStatus`,
              `lastSuccessfulSyncTimestamp`, `lastSyncError`) in Room.
            * Handle API errors gracefully, updating sync metadata.
    * **Assumptions:** `WorkManager` can handle the scheduling and execution guarantees needed. API
      services are robust enough for background use.
    * **Risks:** Complexity in `SyncWorker` input/output data. Correctly handling `WorkManager`
      constraints and lifecycles. Ensuring workers update DB atomically.

3. **Adapt ViewModels:**
    * **File(s):**
        * `mail/src/main/java/net/melisma/mail/ui/MainViewModel.kt`
        * `mail/src/main/java/net/melisma/mail/ui/messagelist/MessageListViewModel.kt` (if exists)
        * `mail/src/main/java/net/melisma/mail/ui/threaddetail/ThreadDetailViewModel.kt`
        * `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt`
    * **Changes:**
        * Ensure all ViewModels collect data from the refactored, DB-only repository `Flow`s.
        * Implement UI states to reflect loading (from initial DB read and background sync),
          success, and error states based on data from `Flow`s and `syncStatus` metadata.
        * Trigger sync requests via Repositories (which then delegate to `SyncEngine`) when cached
          data is stale or missing.
    * **Assumptions:** UI can be made fully reactive to these state changes.
    * **Risks:** Complex UI state management. Ensuring smooth transitions between cached and fresh
      data.

### Phase 2: Decoupling Write Path - Queuing Offline Actions

**Objective:** Enable users to perform actions offline, with these actions being queued and synced
later.

**Changes & Affected Files:**

1. **Refactor Repository Write/Action Methods & Create `ActionUploadWorker`:**
    * **File(s):**
        * `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
        * `data/src/main/java/net/melisma/data/repository/DefaultThreadRepository.kt` (for stubbed
          methods)
        * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt` (if any
          actions are queued)
        * New: `data/src/main/java/net/melisma/data/sync/workers/ActionUploadWorker.kt`
        * DAOs (for optimistic updates and querying pending actions)
        * `data/src/main/java/net/melisma/data/worker/SyncMessageStateWorker.kt` (to be
          refactored/replaced)
    * **Changes:**
        * Methods like `markMessageRead()`, `deleteMessage()`, `sendMessage()`, `createDraft()`,
          `moveMessage()` in repositories will:
          a. Perform optimistic updates to the local Room DB (e.g., change `isRead`, set
          `syncStatus` to `PENDING_UPLOAD`).
          b. Enqueue an `ActionUploadWorker` via the `SyncEngine`, passing necessary details (
          messageId, action type, new data).
        * `ActionUploadWorker`:
            * Queries the DB for items with `syncStatus = PENDING_UPLOAD` or similar.
            * Uses the appropriate `MailApiService` method to perform the action on the server.
            * On success, updates the item's `syncStatus` in Room to `SYNCED` (or removes/updates
              local item as per server response).
            * On failure, updates `lastSyncError`, increments an attempt counter, and implements a
              retry strategy via `WorkManager`.
        * Refactor or replace existing `SyncMessageStateWorker` with this more generic
          `ActionUploadWorker`.
    * **Assumptions:** Optimistic updates are acceptable. Server APIs for actions are idempotent or
      can be handled to avoid duplicate operations on retry.
    * **Risks:** Managing complex action dependencies (e.g., moving a message then deleting it
      offline). Handling conflicts if server state changed before action was uploaded. Ensuring
      `ActionUploadWorker` is robust and retries correctly.

### Phase 3: Building a Robust Sync Engine & Advanced Features

**Objective:** Create a comprehensive `SyncEngine`, implement more sophisticated sync strategies,
and provide clear UI feedback.

**Changes & Affected Files:**

1. **Enhance `SyncEngine`:**
    * **File(s):** `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
    * **Changes:**
        * Implement sync scheduling logic (periodic sync, trigger on network change, on app
          foreground).
        * Manage overall sync state (e.g., "Syncing...", "Last synced: X", "Offline").
        * Handle sync priorities (e.g., user-initiated actions > background folder sync).
        * Implement basic delta sync logic (e.g., using `lastSuccessfulSyncTimestamp` to fetch only
          new items for folders/messages).
        * Expose overall sync status as a `Flow` for UI consumption.
    * **Assumptions:** `WorkManager`'s capabilities are sufficient for these scheduling needs.
    * **Risks:** Complex state machine for `SyncEngine`. Coordinating multiple worker types and
      priorities.

2. **Implement Cache Eviction Strategy:**
    * **File(s):** New: `data/src/main/java/net/melisma/data/sync/workers/CacheCleanupWorker.kt`,
      DAOs.
    * **Changes:**
        * Design and implement a cache eviction policy (e.g., delete messages older than 30 days,
          excluding those with pending actions or drafts).
        * `CacheCleanupWorker` runs periodically to enforce this policy.
    * **Assumptions:** A simple time-based or count-based eviction is sufficient initially.
    * **Risks:** Accidentally deleting important data if logic is flawed. Performance impact of
      querying and deleting large amounts of data.

3. **UI Feedback for Sync Status:**
    * **File(s):** Relevant UI Composables, ViewModels.
    * **Changes:**
        * Display overall sync status from `SyncEngine`.
        * Show item-specific sync status (e.g., "Sending...", "Download failed") based on entity
          `syncStatus` and `lastSyncError`.
        * Provide options for manual "Retry" for failed actions.
    * **Assumptions:** Sync metadata in entities is sufficient for granular UI feedback.
    * **Risks:** Cluttered UI if too much sync information is displayed.

### Phase 4: Advanced Offline Features & Optimizations

**Objective:** Leverage the offline-first foundation to implement more advanced features and
performance optimizations.

* **Unified Inbox (Local Aggregation):** Implement by querying all "Inbox" type folders across
  accounts from the local DB.
* **Full Offline Attachment Access:** Ensure `AttachmentDownloadWorker` is robust and UI correctly
  handles downloaded states.
* **Advanced Local Search (FTS):** Configure Room FTS tables for messages for fast local searching.
* **Optimized Background Sync:** Fine-tune `WorkManager` constraints, batching, and sync intervals
  for battery efficiency.
* **Conflict Resolution (Advanced):** For drafts or other user-modifiable content, implement more
  sophisticated conflict resolution if simple "server wins" is not enough.

---

This plan provides a detailed roadmap. Each phase, and indeed each step, will likely uncover further
nuances and require adjustments. The key is to build incrementally, test thoroughly (even though
tests are out of scope for this plan document), and prioritize user experience at each stage. 