# **Melisma Mail - Caching & Offline-First Implementation Guide**

Version: 2.1 (Move Message Implementation - {{YYYY-MM-DD}})
Date: {{YYYY-MM-DD}}

## **0. Vision & Introduction**

This document outlines the architecture for Melisma Mail as an **offline-first application**. The
core principle is that the local Room database is the **Single Source of Truth (SSoT)** for all
application data. The UI reads exclusively from the database, ensuring a responsive and consistent
experience, even when offline. Network operations are treated as synchronization tasks that update
this local database.

## **I. Current Codebase State (As of Analysis on {{YYYY-MM-DD}})**

This section reflects the validated state of the codebase after direct inspection and recent
changes, including the refactor of DAO and Repository methods, the implementation of
message deletion, the introduction of the Gmail-style thread view, and the implementation of
message move functionality.

### **A. Core Caching & SSoT Foundation:**

* **`:core-db` Module & Database:**
    * `AppDatabase.kt` is at **`version = 7`**.
    * Entities `AccountEntity.kt`, `FolderEntity.kt`, `MessageEntity.kt`, and `MessageBodyEntity.kt`
      are defined.
    * `AccountEntity` includes `val needsReauthentication: Boolean = false`.
    * `MessageEntity` includes `val folderId: String`, `val needsSync: Boolean = false`,
      `val lastSyncError: String? = null`, and `val isLocallyDeleted: Boolean = false`.
    * `MessageBodyEntity` includes `messageId`, `contentType`, `content`, `lastFetchedTimestamp`.
    * **DAOs (`AccountDao`, `FolderDao`, `MessageDao`, `MessageBodyDao`):**
        * Provided via Hilt.
        * Methods previously named with `NonFlow` (e.g., `getMessageByIdNonFlow`) have been
          refactored to `suspend fun ...Suspend` (e.g., `suspend fun getMessageByIdSuspend(...)`).
        * Flow-based methods (e.g., `getMessageById(...) : Flow<...>`) remain for reactive updates.
        * `MessageDao.kt` now includes:
            * `suspend fun markAsLocallyDeleted(...)`
            * `suspend fun deletePermanentlyById(...)`
            *
            `suspend fun updateMessageFolderAndNeedsSync(messageId: String, newFolderId: String, needsSync: Boolean)`:
            For optimistic local update of folder and sync status.
            *
            `suspend fun updateFolderIdAndClearSyncStateOnSuccess(messageId: String, newFolderId: String)`:
            Used by `SyncMessageStateWorker` after a successful API move.
            *
            `suspend fun updateFolderIdSyncErrorAndNeedsSync(messageId: String, folderId: String, errorMessage: String, needsSync: Boolean)`:
            Used by `SyncMessageStateWorker` on a failed API move to revert to `oldFolderId` and set
            error.
    * TypeConverters (`WellKnownFolderTypeConverter`, `StringListConverter`) are present.
    * **Migrations Implemented:**
        * `MIGRATION_1_2`: Adds `messages` table.
        * `MIGRATION_2_3`: Adds `needsSync` to `messages` table.
        * `MIGRATION_3_4`: Adds `needsReauthentication` to `accounts` table.
        * `MIGRATION_4_5`: Adds `lastSyncError` to `messages` table.
        * `MIGRATION_5_6`: Adds `message_bodies` table for `MessageBodyEntity`.
        * `MIGRATION_6_7`: Adds `isLocallyDeleted` to `messages` table.

* **`:core-data` Module Repository Interfaces:**
    * `AccountRepository.kt`:
        * `getAccountByIdNonFlow` refactored to
          `suspend fun getAccountByIdSuspend(accountId: String): Account?`.
        * `overallApplicationAuthState` defined as
          `val overallApplicationAuthState: Flow<OverallApplicationAuthState>`.
    * `MailApiService.kt`:
        *
        `suspend fun moveMessage(messageId: String, currentFolderId: String, destinationFolderId: String): Result<Unit>`
        interface method is present and used.

* **`:backend-google` / `:backend-microsoft` Modules (API Helpers):**
    * `GmailApiHelper.kt`:
        * `moveMessage` implementation uses `users.messages.modify`.
        * Logic refined to explicitly attempt removal of `currentFolderId` (if it's `INBOX` or a
          user label) when moving to a different `destinationFolderId`, to better reflect a "move"
          semantic.
        * Handles "ARCHIVE" destination by removing `INBOX` and potentially the current user label.
          Compares `destinationFolderId` against the string `"ARCHIVE"` as Gmail has no fixed label
          ID for this concept.
        * Includes `isSystemLabel` helper to prevent removal of essential system labels during
          moves.
    * `GraphApiHelper.kt`:
        * `moveMessage` implementation uses the `/me/messages/{messageId}/move` endpoint with
          `destinationId`.

* **`:data` Module Repositories as Synchronizers:**
    * `DefaultAccountRepository.kt`:
        * Implements `suspend fun getAccountByIdSuspend(...)` calling
          `accountDao.getAccountByIdSuspend(...)`.
        * `overallApplicationAuthState` is implemented as a `StateFlow`.
        * `syncGoogleAccount` method correctly sets/clears `needsReauthentication` in DAO.
        * `markAccountForReauthentication` method is implemented and updates the DAO.
    * `DefaultMessageRepository.kt`:
        * `getMessageDetails` (uses `getMessageWithBody`) logic remains as before (handling missing
          entities, API fallbacks).
        * `markMessageRead` updates `MessageEntity.isRead`, sets `needsSync = true`, enqueues
          worker.
        * `starMessage` updates `MessageEntity.isStarred`, sets `needsSync = true`, enqueues worker.
        * `deleteMessage` marks `MessageEntity.isLocallyDeleted = true`, sets `needsSync = true`,
          enqueues worker with `OP_DELETE_MESSAGE`.
        * **`moveMessage(account: Account, messageId: String, newFolderId: String)` IS IMPLEMENTED:
          **
            * Fetches `currentMessage` to get `oldFolderId`.
            * Optimistically updates local DB using
              `messageDao.updateMessageFolderAndNeedsSync(messageId, newFolderId, needsSync = true)`.
            * Enqueues `SyncMessageStateWorker` with `OP_MOVE_MESSAGE`, `messageId`, `newFolderId`,
              and `oldFolderId`.
            * Handles exceptions, updating `lastSyncError` if optimistic local update fails.
        * `enqueueSyncMessageStateWorker` signature updated to include `newFolderId: String? = null`
          and `oldFolderId: String? = null`.
        * Typo `inputDataBilder` fixed to `inputDataBuilder`.
        * **Stubbed/Partially Implemented Methods (Still require full implementation):**
            * `sendMessage`: Returns `NotImplementedError`.
            * `getMessageAttachments`: Returns `flowOf(emptyList())`.
            * `downloadAttachment`: Contains a `TODO`.
            * `createDraftMessage`: Returns `NotImplementedError`.
            * `updateDraftMessage`: Returns `NotImplementedError`.
            * `searchMessages`: Returns `flowOf(emptyList())`.
    * `DefaultFolderRepository.kt`: (Assumed to be largely functional per earlier versions).

* **Paging 3 for Message Lists:**
    * `MessageRemoteMediator.kt`:
        * Uses `MailApiService.getMessagesForFolder` (only `maxResults`) for `LoadType.REFRESH`.
        * Clears and inserts messages into `MessageDao` within a transaction.
        * Returns `endOfPaginationReached = true` for REFRESH, PREPEND, and APPEND.

### **B. Authentication Layer State:**

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

* **WorkManager for \"True Offline\" Sync (Mark Read/Star/Delete/Move):**
    * `SyncMessageStateWorker.kt` exists:
        * DI setup present.
        * API logic for `markMessageRead`, `starMessage`, `deleteMessage`, and **`moveMessage`** is
          present.
        * **Constants Added:** `KEY_NEW_FOLDER_ID`, `KEY_OLD_FOLDER_ID`, `OP_MOVE_MESSAGE`.
        * **`doWork()` handles `OP_MOVE_MESSAGE`:**
            * Retrieves `messageId`, `newFolderId`, `oldFolderId`.
            * Calls `mailApiService.moveMessage(messageId, oldFolderId ?: \"\", newFolderId)`.
        * **`processApiResult()` handles `OP_MOVE_MESSAGE`:**
            * **On Success:** Calls
              `messageDao.updateFolderIdAndClearSyncStateOnSuccess(messageId, newFolderId)`.
            * **On Failure:**
                * Retrieves `oldFolderId`. If available, calls
                  `messageDao.updateFolderIdSyncErrorAndNeedsSync(messageId, oldFolderId, errorMessage, needsSync = true)`
                  to revert folder and set error.
                * If `oldFolderId` is not available, calls
                  `messageDao.updateLastSyncError(messageId, errorMessage)` on the (optimistically
                  updated) `newFolderId`.
        * DAO updates for other sync states (`needsSync`, `lastSyncError`, `isLocallyDeleted`) and
          permanent deletion (for `deleteMessage`) are handled by the worker.
        * Calls `accountRepository.getAccountByIdSuspend`.
    * `DefaultMessageRepository.markMessageRead`, `starMessage`, `deleteMessage`, and *
      *`moveMessage`** enqueue `SyncMessageStateWorker`.

* **On-Demand Message Body Fetching:**
    * `MessageBodyEntity.kt`, `MessageBodyDao.kt`, `AppDatabase.kt` (MIGRATION_5_6) support this.
    * `DefaultMessageRepository.getMessageDetails` fetches and saves bodies.

* **Gmail-style Thread View (Progressive Body Loading):** Implemented as described previously.

### **D. Remaining Unimplemented Core Offline/Caching Features:**

* **Comprehensive Offline Writes:**
    * Composing/sending messages (`DefaultMessageRepository.sendMessage`, `createDraftMessage`,
      `updateDraftMessage`).
* **Attachment Handling:**
    * No `AttachmentEntity` or corresponding DAO.
    * `DefaultMessageRepository.getMessageAttachments` and `downloadAttachment` are not implemented.
* **Local Full-Text Search (FTS):**
    * No `MessageFtsEntity` or FTS setup in Room.
    * `DefaultMessageRepository.searchMessages` is not implemented for local search.

## **II. Unified List of Technical Debt and Other Challenges (Updated {{YYYY-MM-DD}})**

1. **API and Data Model Limitations (DESIGN/IMPLEMENTATION ISSUES):**
    * **API-Model Discrepancies:** Potential for incomplete cached data if `MailApiService` doesn't
      provide all fields.
    * **Stubbed Repository Methods (CODE ISSUE):** Key methods in `DefaultMessageRepository.kt` for
      send, attachments, drafts, and local search are not implemented.
    * **`MailApiService` Pagination Limitation (DESIGN LIMITATION):** `getMessagesForFolder` only
      supports `maxResults`.
    * **Gmail API `moveMessage` Label Nuances (DESIGN/IMPLEMENTATION DETAIL):**
        * Moving to "ARCHIVE" is a conceptual operation (remove INBOX, potentially other user
          labels). `GmailApiHelper` now handles this by comparing `destinationFolderId` to the
          string "ARCHIVE".
        * When moving between user-defined labels (or from INBOX to a user label), the
          `GmailApiHelper` now attempts to remove the `currentFolderId` (source label) to better
          simulate a "move". This relies on `currentFolderId` being correctly passed from the
          repository.

2. **Data Handling and Persistence Concerns (CODE/SCHEMA ISSUES):**
    * **Error Handling in Mappers (Date Parsing):** `MessageMappers.kt` date parsing defaults.
    * **`DefaultMessageRepository.kt` `getMessageWithBody` Robustness:** Interaction between
      API-sourced messages and local cache remains a complex area.
    * **`SyncMessageStateWorker` Error Handling for Move (IMPROVED):** If a `moveMessage` API call
      fails, the worker now attempts to revert the `MessageEntity.folderId` back to the
      `oldFolderId` (if available from input data) and sets the `lastSyncError` and
      `needsSync = true`. This is more robust than leaving the message in an optimistically
      updated (but API-failed) state.

3. **Offline Functionality Gaps (UNIMPLEMENTED FEATURES):**
    * **WorkManager for \"True Offline\" (`SyncMessageStateWorker.kt`):**
        * **REFINEMENT NEEDED (General):** Comprehensive retry policies (e.g., exponential backoff,
          max attempts per error type) for all operations. Current retry is basic.
    * **On-Demand Message Body Fetching (Refinement):** Strategy for re-fetching stale bodies.
    * **Other Offline Writes Not Implemented:** `sendMessage`, draft handling.
    * **Attachment Handling Not Implemented.**
    * **Local Full-Text Search Not Implemented.**

4. **Code Health and Maintenance (CODE ISSUES):**
    * **Log Spam:** Review and reduce diagnostic logging.
    * **Lint Warning in `DefaultAccountRepository.kt`:** Persistent "condition always true"
      warning. (Minor).

## **III. Unified List of Work That Is Still To Be Done to Achieve the Vision (Updated
{{YYYY-MM-DD}})**

This list outlines the necessary steps to realize the full offline-first caching vision.

1. **Implement Comprehensive Offline Write Operations with WorkManager (High Priority):**
    * **A. Delete Message (`DefaultMessageRepository.deleteMessage`) - DONE**
    * **B. Move Message (`DefaultMessageRepository.moveMessage`) - DONE**
    * **C. Drafts & Send Message (`createDraftMessage`, `updateDraftMessage`, `sendMessage`)**

2. **Implement Attachment Handling (High Priority):**
    * **A. DB Schema for Attachments**
    * **B. API Service Updates**
    * **C. `DefaultMessageRepository.getMessageAttachments`**
    * **D. `DefaultMessageRepository.downloadAttachment`**

3. **Enhance `SyncMessageStateWorker.kt` (Ongoing with new operations):**
    * Now handles Mark Read, Star, Delete, and **Move** operations.
    * **Error Handling (Move specific):** Improved to revert `folderId` to `oldFolderId` on API
      failure if `oldFolderId` is available.
    * **Error Handling (General):** Implement detailed error mapping for each operation.
      Differentiate between recoverable/non-recoverable errors for more nuanced retry/failure.
    * **Retry Policy:** Use WorkManager's built-in retry mechanisms more effectively. Configure
      exponential backoff and max retry attempts based on error types.
    * **Input Data:** Ensure all necessary data for each operation is passed correctly.

4. **Implement Local Full-Text Search (FTS) (Medium Priority):** (Details as before)
    * **A. DB Schema for FTS**
    * **B. Update `AppDatabase.kt`**
    * **C. Data Population**
    * **D. `DefaultMessageRepository.searchMessages`**

5. **Refine On-Demand Message Body Fetching (Medium Priority):** (Details as before)
    * Error handling in `DefaultMessageRepository.getMessageWithBody`.
    * Stale body re-fetch strategy.

6. **Address API-Model Discrepancies (Ongoing/As Discovered):** (Details as before)

7. **Code Health and Maintenance (Ongoing):** (Details as before)
    * Review and Reduce Log Spam.
    * Resolve/Suppress Lint Warnings.

8. **Enhance API Capabilities and Advanced Synchronization (Longer Term):** (Details as before)
    * `MailApiService` Pagination.
    * Smart Folder Sync Strategy.
    * Conflict Resolution.

## **IV. Soft Spots, Unknowns, and Research Needs (Updated {{YYYY-MM-DD}})**

* **Soft Spots:**
    * **Paging/RemoteMediator Error Handling:** Robustness of error handling.
    * **`SyncMessageStateWorker.kt` Concurrency & Uniqueness:** Ensuring correct unique work
      policies. `SyncMessageState_${accountId}_${messageId}` should provide per-message sequential
      execution.
    * **Database Transaction Integrity:** Verification of transaction usage.
    * **Gmail `moveMessage` for non-Inbox source folders:** The logic in `GmailApiHelper.kt` now
      attempts to remove the source label if it's a user label or INBOX. This is an improvement but
      relies on `currentFolderId` being accurate. The definition of "system label" in
      `isSystemLabel` might need review for edge cases if more complex label interactions are found.

* **Unknowns (Focus on areas not deeply inspected recently):**
    * Detailed implementation nuances of `MicrosoftTokenPersistenceService.kt` and
      `GoogleTokenPersistenceService.kt`.
    * Full error handling and edge case management in API Helpers for operations *not yet
      implemented* (e.g., send).

* **Research/Investigation Needs:**
    * **WorkManager Best Practices for Complex Sync:** Advanced error handling, managing
      dependencies, progress/status feedback (especially for future multi-step operations like
      send).
    * **Ktor Client Configuration for Robustness:** Review Ktor client setup for all API calls.
    * **Database Performance with FTS.**

This rewritten `CACHE3.md` should now accurately reflect the project's state post-refactoring,
delete implementation, Gmail-style thread view, and the move message implementation, providing an
updated roadmap. 