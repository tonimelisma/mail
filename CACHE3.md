# **Melisma Mail - Caching & Offline-First Implementation Guide**

Version: 2.3 (Thread Repository Refactor - 2024-06-01)
Date: 2024-06-01

## **0. Vision & Introduction**

This document outlines the architecture for Melisma Mail as an **offline-first application**. The
core principle is that the local Room database is the **Single Source of Truth (SSoT)** for all
application data. The UI reads exclusively from the database, ensuring a responsive and consistent
experience, even when offline. Network operations are treated as synchronization tasks that update
this local database.

## **I. Current Codebase State (As of Analysis on 2024-06-01)**

This section reflects the validated state of the codebase after direct inspection and recent
changes, including the refactor of DAO and Repository methods, implementations for
message deletion, move, drafts, attachments (downloading), sending, retrying sends, and the
**refactoring of thread data handling to rely solely on `MessageEntity`**.

### **A. Core Caching & SSoT Foundation:**

* **`:core-db` Module & Database:**
    * `AppDatabase.kt` is at **`version = 8`**.
    * Entities `AccountEntity.kt`, `FolderEntity.kt`, `MessageEntity.kt`, `MessageBodyEntity.kt`, and `AttachmentEntity.kt` are defined.
    * `MailThreadEntity.kt` and `ThreadMessageCrossRef.kt` have been **REMOVED**.
    * `AccountEntity` includes `val needsReauthentication: Boolean = false`.
    * `MessageEntity` includes `val folderId: String`, `val threadId: String?` (crucial for grouping), `val needsSync: Boolean = false`, `val lastSyncError: String? = null`, `val isLocallyDeleted: Boolean = false`, `val isDraft: Boolean`, `val isOutbox: Boolean`, `val draftType: String?`, `val draftParentId: String?`, `val sendAttempts: Int`.
    * `MessageBodyEntity` includes `messageId`, `contentType`, `content`, `lastFetchedTimestamp`.
    * `AttachmentEntity` includes `attachmentId`, `messageId`, `fileName`, `size`, `contentType`, `contentId`, `isInline`, `isDownloaded`, `localFilePath`, `downloadTimestamp`, `downloadError`.
    * **DAOs (`AccountDao`, `FolderDao`, `MessageDao`, `MessageBodyDao`, `AttachmentDao`):**
        * `ThreadDao.kt` has been **REMOVED**.
        * Provided via Hilt.
        * `MessageDao.kt` now includes:
            * `suspend fun markAsLocallyDeleted(...)`
            * `suspend fun deletePermanentlyById(...)`
            * `suspend fun updateMessageFolderAndSyncState(...)` (renamed from `updateMessageFolderAndNeedsSync`)
            * `suspend fun updateFolderIdAndClearSyncStateOnSuccess(...)`
            * `suspend fun updateFolderIdSyncErrorAndNeedsSync(...)`
            * `suspend fun updateDraftContent(...)`
            * `suspend fun prepareForRetry(messageId: String)`
            * `suspend fun updateSendError(messageId: String, error: String)`
            * `fun getMessagesForFolder(accountId: String, folderId: String): Flow<List<MessageEntity>>` (used by `DefaultThreadRepository`)
            * `suspend fun getMessagesCountForFolder(accountId: String, folderId: String): Int` (used by `MessageRemoteMediator`)
            * Methods for `isDraft`, `isOutbox` message properties.
        * `AttachmentDao.kt` includes methods for inserting attachments and updating their download status.
    * TypeConverters (`WellKnownFolderTypeConverter`, `StringListConverter`) are present.
    * **Migrations Implemented:**
        * `MIGRATION_1_2`: Adds `messages` table.
        * `MIGRATION_2_3`: Adds `needsSync` to `messages` table.
        * `MIGRATION_3_4`: Adds `needsReauthentication` to `accounts` table.
        * `MIGRATION_4_5`: Adds `lastSyncError` to `messages` table.
        * `MIGRATION_5_6`: Adds `message_bodies` table for `MessageBodyEntity`.
        * `MIGRATION_6_7`: Adds `isLocallyDeleted` to `messages` table.
        * `MIGRATION_7_8`: Adds new columns to `messages` for draft/outbox, creates `attachments` table.
        * **NOTE:** Any migration previously related to `MailThreadEntity` (e.g., MIGRATION_8_9) has been **REVERTED/REMOVED** as part of the `DefaultThreadRepository` refactor.

* **`:core-data` Module Repository Interfaces (`MessageRepository.kt`, `ThreadRepository.kt`):**
    * `MessageRepository.kt` remains largely the same in terms of its interface.
    * `ThreadRepository.kt`:
        * Interface remains, but its implementation (`DefaultThreadRepository`) now derives thread information from `MessageDao`.
        * Includes `markThreadRead`, `deleteThread`, `moveThread` (currently stubbed in implementation).

* **`:data` Module Repositories as Synchronizers:**
    * `DefaultMessageRepository.kt`:
        * (Details as previously, no major changes from this specific refactor).
    * `DefaultThreadRepository.kt`:
        * **REFACTORED:** No longer uses dedicated thread entities or DAO.
        * Injects `MessageDao` and `AppDatabase`.
        * `setTargetFolderForThreads` observes `messageDao.getMessagesForFolder()`.
        * Implements `groupAndMapMessagesToMailThreads` to transform `List<MessageEntity>` into domain `List<MailThread>`.
        * `launchThreadFetchJobInternal` fetches messages (not threads) from API, saves them via `messageDao.insertOrUpdateMessages()`.
        * `markThreadRead`, `deleteThread`, `moveThread` are **STUBBED** and need full implementation by operating on messages from `MessageDao`.

* **Paging 3 for Message Lists:**
    * `MessageRemoteMediator.kt`:
        * **IMPROVED:** `initialize()` method now checks `messageDao.getMessagesCountForFolder()` to determine `InitializeAction.LAUNCH_INITIAL_REFRESH` or `InitializeAction.SKIP_INITIAL_REFRESH`.
        * Uses `MailApiService.getMessagesForFolder` for `LoadType.REFRESH`.
        * Uses `messageDao.insertOrUpdateMessages` (upsert) within a transaction during `REFRESH`.
        * Still returns `endOfPaginationReached = true` for REFRESH, PREPEND, and APPEND.

### **B. Authentication Layer State:** (Largely unchanged)

### **C. Implemented Core Offline/Caching Features & UI Data Handling:**

* **WorkManager for "True Offline" Sync:** (Details as previously)
* **On-Demand Message Body Fetching:** (Details as previously)
* **Gmail-style Thread View (Progressive Body Loading):** Implemented.
* **Thread List Display:** Now sources data from `DefaultThreadRepository` which, in turn, gets messages from `MessageDao` and groups them into threads. UI observes `threadDataState` from `DefaultThreadRepository`.

### **D. Remaining Unimplemented Core Offline/Caching Features:**

* **Local Full-Text Search (FTS):** (Details as previously)
* **Thread-Level Actions Implementation:** Full implementation of `markThreadRead`, `deleteThread`, `moveThread` in `DefaultThreadRepository` by operating on messages.

## **II. Unified List of Technical Debt and Other Challenges (Updated 2024-06-01)**

1.  **API and Data Model Limitations (DESIGN/IMPLEMENTATION ISSUES):**
    *   (Existing points remain valid)
    *   **NEW/HIGHLIGHTED: `DefaultThreadRepository.groupAndMapMessagesToMailThreads` Performance:** For folders with a very large number of messages that form many threads, or for very large individual threads, the in-memory grouping and mapping could be computationally intensive or memory-heavy. This needs monitoring, especially on lower-end devices.
    *   **NEW: Complexity of Thread-Level Actions:** Implementing actions like "move thread" or "delete thread" now requires fetching all relevant messages for that thread, updating them, and then potentially batching these updates for `SyncMessageStateWorker`. This adds complexity compared to a single API call for a thread ID.

2.  **Data Handling and Persistence Concerns (CODE/SCHEMA ISSUES):**
    *   (Existing points remain valid)
    *   **`MessageRemoteMediator` Refresh Strategy:** While `initialize()` is improved, the overall strategy for when and how `REFRESH` is triggered for threads (now derived from messages) versus individual paged messages might need further thought to avoid redundant fetches if `DefaultThreadRepository` also fetches.

3.  **Offline Functionality Gaps (UNIMPLEMENTED FEATURES / ENHANCEMENTS):**
    *   **WorkManager for "True Offline" (`SyncMessageStateWorker.kt`):**
        *   (Existing points remain valid)
        *   **NEW: Thread Action Sync:** The `SyncMessageStateWorker` will need to correctly handle batch operations on messages if thread-level actions are implemented by modifying multiple messages. Current worker ops are message-centric.
    *   **On-Demand Message Body Fetching (Refinement):** (Details as previously)
    *   **Attachment Handling:** (Details as previously)
    *   **Local Full-Text Search Not Implemented.**
    *   **NEW: Full Implementation of Thread Actions:** `markThreadRead`, `deleteThread`, `moveThread` in `DefaultThreadRepository` are currently stubs.

4.  **Code Health and Maintenance (CODE ISSUES):**
    *   (Existing points remain valid)

## **III. Unified List of Work That Is Still To Be Done to Achieve the Vision (Updated 2024-06-01)**

1.  **Implement Comprehensive Offline Write Operations with WorkManager (High Priority):**
    *   **A. Message-Level Actions (`DefaultMessageRepository`) - Largely DONE (Ongoing Refinements)**
        *   (Sub-points as previously)
    *   **B. Thread-Level Actions (`DefaultThreadRepository`) - NEW/TODO**
        *   **Implement `markThreadRead`:**
            *   Fetch all `MessageEntity` items for the given `threadId` via `MessageDao`.
            *   Update their `isRead` status.
            *   Save them back via `messageDao.insertOrUpdateMessages`.
            *   Enqueue `SyncMessageStateWorker` for each affected message to sync `isRead` state.
        *   **Implement `deleteThread`:**
            *   Fetch all `MessageEntity` items for the `threadId`.
            *   Mark them as `isLocallyDeleted = true` (or call appropriate API and then update DB).
            *   Save them back via `MessageDao`.
            *   Enqueue `SyncMessageStateWorker` for each message for deletion.
        *   **Implement `moveThread`:**
            *   Fetch all `MessageEntity` items for the `threadId` from the `currentFolderId`.
            *   Update their `folderId` to `destinationFolderId` and set `needsSync = true`.
            *   Save them back via `MessageDao`.
            *   Enqueue `SyncMessageStateWorker` for each message to be moved.

2.  **Implement Attachment Handling (High Priority):** (Details as previously)

3.  **Enhance `SyncMessageStateWorker.kt` (Ongoing with new operations):**
    *   (Existing points remain valid)
    *   **NEW: Consider Batch Operations:** If APIs support batch operations for marking messages read/deleted/moved, the worker could be enhanced. Otherwise, it will process a queue of individual message operations resulting from a thread action.

4.  **Implement Local Full-Text Search (FTS) (Medium Priority):** (Details as previously)

5.  **Refine On-Demand Message Body Fetching (Medium Priority):** (Details as previously)

6.  **Address API-Model Discrepancies (Ongoing/As Discovered):** (Details as previously)

7.  **Code Health and Maintenance (Ongoing):**
    *   (Existing points remain valid)
    *   **Database Migration:** `AppDatabase.version` is now 8. Verified that previous thread-specific migrations are obsolete/removed.

8.  **Enhance API Capabilities and Advanced Synchronization (Longer Term):**
    *   (Existing points remain valid)
    *   **Performance Monitoring for `groupAndMapMessagesToMailThreads`:** Actively monitor and optimize if it becomes a bottleneck.

## **IV. Soft Spots, Unknowns, and Research Needs (Updated 2024-06-01)**

*   **Soft Spots:**
    *   (Existing points remain valid)
    *   **Performance of `DefaultThreadRepository.groupAndMapMessagesToMailThreads`:** (As noted in Tech Debt).
    *   **Complexity of `SyncMessageStateWorker` for thread actions:** Managing potentially many individual message operations stemming from a single thread action.

*   **Unknowns (Focus on areas not deeply inspected recently):** (Existing points remain valid)

*   **Research/Investigation Needs:**
    *   (Existing points remain valid)
    *   **Efficient Batching for DAO/API with Thread Actions:** Investigate best ways to update/sync multiple messages that form a thread without overwhelming the DB or network.

This rewritten `CACHE3.md` should now accurately reflect the project's state post-refactoring of `DefaultThreadRepository` and the move away from dedicated thread entities. 