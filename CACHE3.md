# **Melisma Mail - Caching & Offline-First Implementation Guide**

Version: 2.2 (Drafts, Attachments, Send/Retry Logic - 2024-06-01)
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
message deletion, move, drafts, attachments (downloading), sending, and retrying sends.

### **A. Core Caching & SSoT Foundation:**

* **`:core-db` Module & Database:**
    * `AppDatabase.kt` is at **`version = 7`** (pending update for `AttachmentEntity` and related DAOs if not already included).
        * **Note:** The last commit added `AttachmentEntity`, `AttachmentDao`, and likely necessitated a DB version bump and migration. This needs verification.
    * Entities `AccountEntity.kt`, `FolderEntity.kt`, `MessageEntity.kt`, `MessageBodyEntity.kt`, and **`AttachmentEntity.kt`** are defined.
    * `AccountEntity` includes `val needsReauthentication: Boolean = false`.
    * `MessageEntity` includes `val folderId: String`, `val needsSync: Boolean = false`, `val lastSyncError: String? = null`, `val isLocallyDeleted: Boolean = false`, `val isDraft: Boolean`, `val isOutbox: Boolean`, `val draftType: String?`, `val draftParentId: String?`, `val sendAttempts: Int`.
    * `MessageBodyEntity` includes `messageId`, `contentType`, `content`, `lastFetchedTimestamp`.
    * `AttachmentEntity` includes `attachmentId`, `messageId`, `fileName`, `size`, `contentType`, `contentId`, `isInline`, `isDownloaded`, `localFilePath`, `downloadTimestamp`, `downloadError`.
    * **DAOs (`AccountDao`, `FolderDao`, `MessageDao`, `MessageBodyDao`, `AttachmentDao`):**
        * Provided via Hilt.
        * `MessageDao.kt` now includes:
            * `suspend fun markAsLocallyDeleted(...)`
            * `suspend fun deletePermanentlyById(...)`
            * `suspend fun updateMessageFolderAndNeedsSync(...)`
            * `suspend fun updateFolderIdAndClearSyncStateOnSuccess(...)`
            * `suspend fun updateFolderIdSyncErrorAndNeedsSync(...)`
            * `suspend fun updateDraftContent(...)`
            * **`suspend fun prepareForRetry(messageId: String)`:** Clears `lastSendError`, sets `needsSync = 1`, resets `sendAttempts = 0`.
            * `suspend fun updateSendError(messageId: String, error: String)` (parameter `error` is non-null).
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
        * **PENDING/VERIFY:** Migration for `AttachmentEntity` and `MessageEntity` changes (e.g., `sendAttempts`, `isOutbox`, `isDraft`, etc.) if `AppDatabase.version` is still 7.

* **`:core-data` Module Repository Interfaces (`MessageRepository.kt`):**
    * `suspend fun sendMessage(draft: MessageDraft, account: Account): Result<String>`
    * `suspend fun getMessageAttachments(accountId: String, messageId: String): Flow<List<Attachment>>`
    * `suspend fun downloadAttachment(accountId: String, messageId: String, attachment: Attachment): Flow<String?>` (Path to downloaded file)
    * `suspend fun createDraftMessage(accountId: String, draftDetails: MessageDraft): Result<Message>`
    * `suspend fun updateDraftMessage(accountId: String, messageId: String, draftDetails: MessageDraft): Result<Message>`
    * `fun searchMessages(accountId: String, query: String, folderId: String?): Flow<List<Message>>`
    * **NEW:** `suspend fun retrySendMessage(accountId: String, messageId: String): Result<Unit>`

* **`:data` Module Repositories as Synchronizers:**
    * `DefaultMessageRepository.kt`:
        * `getMessageDetails` (uses `getMessageWithBody`) logic remains.
        * `markMessageRead`, `starMessage`, `deleteMessage`, `moveMessage` implementations enqueue `SyncMessageStateWorker`.
        * **`sendMessage` IMPLEMENTED:** Creates an outbox `MessageEntity`, stores `MessageBodyEntity`, enqueues `SyncMessageStateWorker` with `OP_SEND_MESSAGE`.
        * **`createDraftMessage` IMPLEMENTED:** Creates a draft `MessageEntity`, stores `MessageBodyEntity`, enqueues `SyncMessageStateWorker` with `OP_CREATE_DRAFT`.
        * **`updateDraftMessage` IMPLEMENTED:** Updates draft `MessageEntity` and `MessageBodyEntity`, enqueues `SyncMessageStateWorker` with `OP_UPDATE_DRAFT`.
        * **`getMessageAttachments` IMPLEMENTED:** Fetches from local DB and API (if needed), stores API results in `AttachmentDao`.
        * **`downloadAttachment` IMPLEMENTED:** Calls `MailApiService` to download, saves file to internal storage (`filesDir/attachments/{messageId}/{fileName}`), updates `AttachmentDao` with path and status. Returns `Flow<String?>` for file path.
        * **`searchMessages` IMPLEMENTED (API Only):** Calls `MailApiService.searchMessages`. Does not use local FTS.
        * **`retrySendMessage` IMPLEMENTED:** Reconstructs `MessageDraft` (with limitations, see Tech Debt), calls `messageDao.prepareForRetry`, re-enqueues `SyncMessageStateWorker` for `OP_SEND_MESSAGE`.
        * `enqueueSyncMessageStateWorker` updated to handle new operations and data (`draftData`, `sentFolderId`).

* **Paging 3 for Message Lists:**
    * `MessageRemoteMediator.kt`:
        * Uses `MailApiService.getMessagesForFolder` for `LoadType.REFRESH`.
        * **IMPROVED:** Uses `messageDao.insertOrUpdateMessages` (upsert) within a transaction during `REFRESH` instead of clear and insert for the specific account/folder.
        * Still returns `endOfPaginationReached = true` for REFRESH, PREPEND, and APPEND.

### **B. Authentication Layer State:** (Largely unchanged from previous analysis)

* **Google Authentication (`:backend-google`):**
    * `GoogleAuthManager.kt` and `DefaultAccountRepository.syncGoogleAccount` handle token refresh
      and `needsReauthentication`.
* **Microsoft Authentication (`:backend-microsoft` - MSAL Version 6.0.0):**
    * `MicrosoftAuthManager.kt`: Robust `signOut`. MSAL PII logging enabled.
    * `MicrosoftAccountMappers.kt`: Maps `IAccount.id`.
    * `ActiveMicrosoftAccountHolder.kt`: SharedPreferences for active ID.
    * `MicrosoftAccountRepository.kt`: Implements `getAccountByIdSuspend`, `syncAccount` handles
      MSAL errors. `overallApplicationAuthState` reflects `AccountDao` and MSAL state.

### **C. Implemented Core Offline/Caching Features & UI Data Handling:**

* **WorkManager for "True Offline" Sync (Mark Read/Star/Delete/Move/Drafts/Send):**
    * `SyncMessageStateWorker.kt` exists:
        * Handles `OP_MARK_READ`, `OP_STAR_MESSAGE`, `OP_DELETE_MESSAGE`, `OP_MOVE_MESSAGE`.
        * **NEW:** Handles `OP_CREATE_DRAFT`, `OP_UPDATE_DRAFT`, `OP_SEND_MESSAGE`.
        * `processApiResult()` for `OP_MOVE_MESSAGE` reverts `folderId` on failure.
        * **NEW:** `doWork()` for `OP_SEND_MESSAGE` uses `draftData` to call `apiService.sendMessage`. Updates `MessageEntity` (e.g., clears `isOutbox`, sets `sentTimestamp`, updates `messageId` if changed by server) on success. Handles send errors.
        * **NEW:** `doWork()` for `OP_CREATE_DRAFT` / `OP_UPDATE_DRAFT` syncs draft state with server.
        * DAO updates for other sync states (`needsSync`, `lastSyncError`, `isLocallyDeleted`) and
          permanent deletion (for `deleteMessage`) are handled by the worker.
        * Calls `accountRepository.getAccountByIdSuspend`.
    * `DefaultMessageRepository.markMessageRead`, `starMessage`, `deleteMessage`, and *
      *`moveMessage`** enqueue `SyncMessageStateWorker`.

* **On-Demand Message Body Fetching:**
    * `MessageBodyEntity.kt`, `MessageBodyDao.kt`, `AppDatabase.kt` (MIGRATION_5_6) support this.
    * `DefaultMessageRepository.getMessageDetails` fetches and saves bodies.

* **Gmail-style Thread View (Progressive Body Loading):** Implemented.

### **D. Remaining Unimplemented Core Offline/Caching Features:**

* **Local Full-Text Search (FTS):**
    * No `MessageFtsEntity` or FTS setup in Room.
    * `DefaultMessageRepository.searchMessages` is API-only.

## **II. Unified List of Technical Debt and Other Challenges (Updated 2024-06-01)**

1.  **API and Data Model Limitations (DESIGN/IMPLEMENTATION ISSUES):**
    *   **API-Model Discrepancies:** Potential for incomplete cached data if `MailApiService` doesn't provide all fields.
    *   **`MailApiService` Pagination Limitation (DESIGN LIMITATION):** `getMessagesForFolder` only supports `maxResults`.
    *   **Gmail API `moveMessage` Label Nuances:** (As previously noted).
    *   **NEW: `DefaultMessageRepository.retrySendMessage` Limitations:**
        *   **CC/BCC Reconstruction:** `MessageEntity` does not store CC/BCC recipients. `retrySendMessage` currently reconstructs `MessageDraft` without them. This means a retried message will lose original CC/BCC recipients unless addressed.
        *   **Attachment Reconstruction:** `MessageDraft` in `retrySendMessage` is created with an empty `attachments` list. The worker needs a strategy to handle attachments for retries (e.g., if they were uploaded and have IDs, or if local paths need to be re-processed). The current implementation assumes the worker handles attachments based on the initial draft or existing server state, which might be insufficient for a robust retry.
    *   **NEW: Database Versioning & Migrations:** The addition of `AttachmentEntity` and changes to `MessageEntity` (e.g., `sendAttempts`, draft/outbox flags) require a database migration. If `AppDatabase.version` is still 7, this is a critical missing step.

2.  **Data Handling and Persistence Concerns (CODE/SCHEMA ISSUES):**
    *   **Error Handling in Mappers (Date Parsing):** `MessageMappers.kt` date parsing defaults.
    *   **`DefaultMessageRepository.kt` `getMessageWithBody` Robustness:** Interaction between API-sourced messages and local cache.
    *   **`SyncMessageStateWorker` Error Handling for Move (IMPROVED):** (As previously noted).
    *   **NEW: `DefaultMessageRepository.downloadAttachment` Robustness:**
        *   **File Management:** Current implementation saves to `filesDir/attachments/{messageId}/{fileName}`. This doesn't handle potential filename conflicts if multiple attachments have the same name for one message. It also doesn't consider storage limits or cleanup of old attachments.
        *   **Progress Reporting:** The `Flow<String?>` emits the path on completion or null on error. It doesn't provide intermediate progress updates, which would be crucial for large files.
    *   **NEW: `MessageRemoteMediator` Refresh Strategy:**
        *   While `REFRESH` now uses an upsert strategy (which is good), it still *always* triggers a network fetch when a folder is selected for the first time or the Pager is re-established (`MessageRemoteMediator` is created and `load(REFRESH)` is called).
        *   A more optimized approach would be to allow `SKIP_INITIAL_REFRESH` if the local data for that folder is deemed recent enough or valid (e.g., based on a timestamp of last successful sync for that folder).

3.  **Offline Functionality Gaps (UNIMPLEMENTED FEATURES / ENHANCEMENTS):**
    *   **WorkManager for "True Offline" (`SyncMessageStateWorker.kt`):**
        *   **REFINEMENT NEEDED (General):** Comprehensive retry policies (e.g., exponential backoff, max attempts per error type) for all operations. Current retry is basic.
        *   **Send Error Visibility:** `MessageEntity.lastSendError` is populated by the worker on send failure, but there's no UI mechanism yet to display this to the user for outbox items.
        *   **User-Initiated Retry:** No UI for the user to manually trigger a retry for a failed message in the outbox (which would call `DefaultMessageRepository.retrySendMessage`).
    *   **On-Demand Message Body Fetching (Refinement):** Strategy for re-fetching stale bodies.
    *   **Attachment Handling:**
        *   **`downloadAttachment`:** Lacks progress reporting and robust file management (see Tech Debt). UI for showing progress and opening downloaded files is pending.
    *   **Local Full-Text Search Not Implemented.**

4.  **Code Health and Maintenance (CODE ISSUES):**
    *   **Log Spam:** Review and reduce diagnostic logging.
    *   **Lint Warning in `DefaultAccountRepository.kt`:** Persistent "condition always true" warning. (Minor).
    *   **TODOs:** The codebase likely contains `TODO` comments from the recent commit that need addressing.

## **III. Unified List of Work That Is Still To Be Done to Achieve the Vision (Updated 2024-06-01)**

This list outlines the necessary steps to realize the full offline-first caching vision.

1.  **Implement Comprehensive Offline Write Operations with WorkManager (High Priority):**
    *   **A. Delete Message (`DefaultMessageRepository.deleteMessage`) - DONE**
    *   **B. Move Message (`DefaultMessageRepository.moveMessage`) - DONE**
    *   **C. Drafts & Send Message (`createDraftMessage`, `updateDraftMessage`, `sendMessage`) - DONE (Initial Implementation)**
        *   **Refinement for `retrySendMessage` (see Tech Debt):** Address CC/BCC and attachment reconstruction.
        *   **Outbox UI:** Display send errors from `MessageEntity.lastSendError`.
        *   **User-Initiated Retry UI:** Allow users to trigger `DefaultMessageRepository.retrySendMessage` for failed outbox items.

2.  **Implement Attachment Handling (High Priority):**
    *   **A. DB Schema for Attachments (`AttachmentEntity`, `AttachmentDao`) - DONE**
    *   **B. API Service Updates (`MailApiService.downloadAttachment`, `MailApiService.getMessageAttachments`) - DONE**
    *   **C. `DefaultMessageRepository.getMessageAttachments` - DONE**
    *   **D. `DefaultMessageRepository.downloadAttachment` - DONE (Initial Implementation)**
        *   **Enhancements (see Tech Debt):** Implement robust file management (naming, conflicts, storage limits), progress reporting.
        *   **UI Integration:** Display download progress, allow opening of downloaded attachments.

3.  **Enhance `SyncMessageStateWorker.kt` (Ongoing with new operations):**
    *   Now handles Mark Read, Star, Delete, Move, Create/Update Draft, and Send operations.
    *   **Error Handling (Move specific):** Improved.
    *   **Error Handling (Send specific):** Populates `lastSendError` in `MessageEntity`.
    *   **Error Handling (General):** Implement detailed error mapping for each operation. Differentiate between recoverable/non-recoverable errors.
    *   **Retry Policy:** Use WorkManager's built-in retry mechanisms more effectively. Configure exponential backoff and max retry attempts.
    *   **Input Data:** Ensure all necessary data for each operation is passed correctly.

4.  **Implement Local Full-Text Search (FTS) (Medium Priority):**
    *   **A. DB Schema for FTS (`MessageFtsEntity`)**
    *   **B. Update `AppDatabase.kt` (add FTS table, trigger to populate)**
    *   **C. Data Population strategy for FTS table.**
    *   **D. `DefaultMessageRepository.searchMessages` to query local FTS first, then fallback to API if needed/requested.**

5.  **Refine On-Demand Message Body Fetching (Medium Priority):** (Details as before)
    *   Error handling in `DefaultMessageRepository.getMessageWithBody`.
    *   Stale body re-fetch strategy.

6.  **Address API-Model Discrepancies (Ongoing/As Discovered):** (Details as before)

7.  **Code Health and Maintenance (Ongoing):**
    *   Review and Reduce Log Spam.
    *   Resolve/Suppress Lint Warnings.
    *   **Address `TODO` comments from recent commits.**
    *   **Database Migration:** Ensure `AppDatabase.version` is incremented and a migration is written for `AttachmentEntity` and `MessageEntity` changes if not already done.

8.  **Enhance API Capabilities and Advanced Synchronization (Longer Term):**
    *   `MailApiService` Pagination for `getMessagesForFolder`.
    *   Smart Folder Sync Strategy (e.g., conditional `SKIP_INITIAL_REFRESH` in `MessageRemoteMediator` based on cache recency).
    *   Conflict Resolution for concurrent modifications.

## **IV. Soft Spots, Unknowns, and Research Needs (Updated 2024-06-01)**

*   **Soft Spots:**
    *   **Paging/RemoteMediator Error Handling & Refresh Strategy:** Needs more sophisticated cache-aware refresh logic.
    *   **`SyncMessageStateWorker.kt` Concurrency & Uniqueness:** Policies seem okay (`SyncMessageState_${accountId}_${messageId}`), but complex interactions with retries and new draft/send operations warrant ongoing observation.
    *   **Database Transaction Integrity:** Verification of transaction usage, especially with new draft/attachment ops.
    *   **Gmail `moveMessage` for non-Inbox source folders:** (As previously noted).
    *   **NEW: Completeness of `MessageDraft` reconstruction in `retrySendMessage`:** Relies heavily on data available in `MessageEntity` and `MessageBodyEntity`. Gaps in CC/BCC/Attachments are current soft spots.
    *   **NEW: Attachment File Handling:** Local file paths, potential for conflicts, storage management.

*   **Unknowns (Focus on areas not deeply inspected recently):**
    *   Detailed implementation nuances of `MicrosoftTokenPersistenceService.kt` and `GoogleTokenPersistenceService.kt`.
    *   Full error handling and edge case management in API Helpers for *all* implemented operations (send, draft sync, attachment downloads).

*   **Research/Investigation Needs:**
    *   **WorkManager Best Practices for Complex Sync:** Advanced error handling, managing dependencies (e.g., ensuring draft exists before sending), progress/status feedback for multi-step operations (send with attachments).
    *   **Ktor Client Configuration for Robustness:** Review Ktor client setup for all API calls.
    *   **Database Performance with FTS.**
    *   **Secure and efficient local file storage strategy for attachments.**

This rewritten `CACHE3.md` should now accurately reflect the project's state post-refactoring,
delete implementation, Gmail-style thread view, move message implementation, and the recent additions for drafts, attachments, and sending logic, providing an updated roadmap. 