# **Melisma Mail - Caching & Offline-First Implementation Guide**

Version: 1.9 (Reflects Delete Message Implementation - 2024-07-30)
Date: 2024-07-30

## **0. Vision & Introduction**

This document outlines the architecture for Melisma Mail as an **offline-first application**. The
core principle is that the local Room database is the **Single Source of Truth (SSoT)** for all
application data. The UI reads exclusively from the database, ensuring a responsive and consistent
experience, even when offline. Network operations are treated as synchronization tasks that update
this local database.

## **I. Current Codebase State (As of Analysis on 2024-07-30)**

This section reflects the validated state of the codebase after direct inspection and recent
changes, including the refactor of DAO and Repository methods to use `suspend` functions and `Flow`
for asynchronous data streams, moving away from direct blocking calls, and the implementation of
message deletion.

### **A. Core Caching & SSoT Foundation:**

* **`:core-db` Module & Database:**
    * `AppDatabase.kt` is at **`version = 7`**.
    * Entities `AccountEntity.kt`, `FolderEntity.kt`, `MessageEntity.kt`, and `MessageBodyEntity.kt`
      are defined.
    * `AccountEntity` includes `val needsReauthentication: Boolean = false`.
    * `MessageEntity` includes `val needsSync: Boolean = false`,
      `val lastSyncError: String? = null`, and `val isLocallyDeleted: Boolean = false`.
    * `MessageBodyEntity` includes `messageId`, `contentType`, `content`, `lastFetchedTimestamp`.
    * **DAOs (`AccountDao`, `FolderDao`, `MessageDao`, `MessageBodyDao`):**
        * Provided via Hilt.
        * Methods previously named with `NonFlow` (e.g., `getMessageByIdNonFlow`) have been
          refactored to `suspend fun ...Suspend` (e.g., `suspend fun getMessageByIdSuspend(...)`).
        * Flow-based methods (e.g., `getMessageById(...) : Flow<...>`) remain for reactive updates.
      * `MessageDao.kt` now includes `suspend fun markAsLocallyDeleted(...)` and
        `suspend fun deletePermanentlyById(...)`.
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
          `val overallApplicationAuthState: Flow<OverallApplicationAuthState>`. (Note:
          Implementations may use `StateFlow`).

* **`:data` Module Repositories as Synchronizers:**
    * `DefaultAccountRepository.kt`:
        * Implements `suspend fun getAccountByIdSuspend(...)` calling
          `accountDao.getAccountByIdSuspend(...)`.
        * `overallApplicationAuthState` is implemented as a `StateFlow`, derived from `AccountDao`
          observations.
        * `syncGoogleAccount` method correctly sets/clears `needsReauthentication` in DAO.
        * `markAccountForReauthentication` method is implemented and updates the DAO.
    * `DefaultMessageRepository.kt`:
        * `getMessageDetails` (which uses `getMessageWithBody`) now uses `channelFlow` and calls the
          new `suspend fun` DAO methods (`messageDao.getMessageByIdSuspend`,
          `messageBodyDao.getMessageBodyByIdSuspend`) and `accountRepository.getAccountByIdSuspend`.
          It applies `.flowOn(ioDispatcher)`.
        * `markMessageRead` updates `MessageEntity.isRead`, sets `needsSync = true` in DAO, and
          enqueues `SyncMessageStateWorker`.
        * `starMessage` updates `MessageEntity.isStarred`, sets `needsSync = true` in DAO, and
          enqueues `SyncMessageStateWorker`.
      * `deleteMessage` now marks `MessageEntity.isLocallyDeleted = true`, sets `needsSync = true`
        in DAO, and enqueues `SyncMessageStateWorker` with `OP_DELETE_MESSAGE`.
        * Typo `inputDataBilder` is confirmed fixed to `inputDataBuilder` in
          `enqueueSyncMessageStateWorker`.
        * **Stubbed/Partially Implemented Methods (Still require full implementation):**
            * `moveMessage`: Returns `NotImplementedError`.
            * `sendMessage`: Returns `NotImplementedError`.
            * `getMessageAttachments`: Returns `flowOf(emptyList())`.
            * `downloadAttachment`: Contains a `TODO`.
            * `createDraftMessage`: Returns `NotImplementedError`.
            * `updateDraftMessage`: Returns `NotImplementedError`.
            * `searchMessages`: Returns `flowOf(emptyList())`.
    * `DefaultFolderRepository.kt`: (Assumed to be largely functional per earlier versions, focusing
      on SSoT from DB for folders - not re-validated in this pass).

* **Paging 3 for Message Lists:**
    * `MessageRemoteMediator.kt`:
        * Uses `MailApiService.getMessagesForFolder` (only `maxResults`) for `LoadType.REFRESH`.
        * Clears and inserts messages into `MessageDao` within a transaction.
        * Returns `endOfPaginationReached = true` for REFRESH, PREPEND, and APPEND.
    * `MailApiService.kt` interface's `getMessagesForFolder` signature confirmed to only accept
      `maxResults`.

### **B. Authentication Layer State:**

* **Google Authentication (`:backend-google`):**
    * `GoogleAuthManager.kt` and `DefaultAccountRepository.syncGoogleAccount` appear to handle token
      refresh and updating `needsReauthentication` flag correctly.
* **Microsoft Authentication (`:backend-microsoft` - MSAL Version 6.0.0):**
    * `MicrosoftAuthManager.kt`:
        * `signOut` method has been updated and is more robust, using `CompletableDeferred` and
          local cleanup.
        * MSAL PII logging is enabled.
    * `MicrosoftAccountMappers.kt`: Uses `IAccount.id` for `Account.id`. Defaults
      `Account.needsReauthentication` to `false`.
    * `ActiveMicrosoftAccountHolder.kt`: Loads/saves active ID from SharedPreferences.
    * `MicrosoftAccountRepository.kt`:
        * Implements `suspend fun getAccountByIdSuspend(...)` calling
          `accountDao.getAccountByIdSuspend(...)`.
        * `syncAccount` calls its own `markAccountForReauthentication` (updates DAO) on MSAL errors.
        * `overallApplicationAuthState` is implemented as a `StateFlow<OverallApplicationAuthState>`
          using `combine` and `stateIn`, reflecting `needsReauthentication` from `AccountDao` and
          MSAL state.

### **C. Implemented Core Offline/Caching Features:**

* **WorkManager for "True Offline" Sync (Mark Read/Star/Delete):**
    * `SyncMessageStateWorker.kt` exists:
        * DI setup is present.
      * API logic for `markMessageRead`, `starMessage`, and `deleteMessage` is present.
      * DAO updates for sync state (`needsSync`, `lastSyncError`, `isLocallyDeleted`) and permanent
        deletion (for `deleteMessage`) are handled by the worker.
        * Calls `accountRepository.getAccountByIdSuspend` (refactored).
    * `DefaultMessageRepository.markMessageRead`, `starMessage`, and `deleteMessage` enqueue
      `SyncMessageStateWorker`.
* **On-Demand Message Body Fetching:**
    * `MessageBodyEntity.kt` exists.
    * `MessageBodyDao.kt` exists and is provided via Hilt DI (with `suspend` methods).
    * `AppDatabase.kt` includes `MessageBodyEntity` and `MIGRATION_5_6`.
    * `DefaultMessageRepository.getMessageDetails` fetches and saves message bodies using suspend
      functions.

### **D. Remaining Unimplemented Core Offline/Caching Features:**

* **Comprehensive Offline Writes:**
    * Moving messages (`DefaultMessageRepository.moveMessage`).
    * Composing/sending messages (`DefaultMessageRepository.sendMessage`, `createDraftMessage`,
      `updateDraftMessage`).
* **Attachment Handling:**
    * No `AttachmentEntity` or corresponding DAO.
    * `DefaultMessageRepository.getMessageAttachments` and `downloadAttachment` are not implemented.
* **Local Full-Text Search (FTS):**
    * No `MessageFtsEntity` or FTS setup in Room.
    * `DefaultMessageRepository.searchMessages` is not implemented for local search.

## **II. Unified List of Technical Debt and Other Challenges (Verified 2024-07-30)**

1. **API and Data Model Limitations (DESIGN/IMPLEMENTATION ISSUES):**
    * **API-Model Discrepancies:** Potential for incomplete cached data if `MailApiService` doesn't
      provide all fields defined in `core_data.model.Message` and other domain models.
    * **Stubbed Repository Methods (CODE ISSUE):** Key methods in `DefaultMessageRepository.kt` for
      move, send, attachments, drafts, and local search are not implemented.
    * **`MailApiService` Pagination Limitation (DESIGN LIMITATION):** `getMessagesForFolder` only
      supports `maxResults`, preventing network-driven APPEND/PREPEND in `MessageRemoteMediator`.

2. **Data Handling and Persistence Concerns (CODE/SCHEMA ISSUES):**
    * **Error Handling in Mappers (Date Parsing):** `MessageMappers.kt` date parsing defaults might
      need careful UI handling for invalid dates.
    * **`DefaultMessageRepository.kt` `getMessageWithBody` Robustness:** While refactored to use
      suspend functions, the logic for handling network errors during body fetch vs. local cache
      needs ongoing validation.

3. **Offline Functionality Gaps (UNIMPLEMENTED FEATURES):**
    * **WorkManager for "True Offline" (`SyncMessageStateWorker.kt`):**
        * **REFINEMENT NEEDED:** Robust error handling (specific exceptions, network conditions) and
          comprehensive retry policies (e.g., exponential backoff, max attempts) within
          `SyncMessageStateWorker.kt` need to be designed and implemented for all supported and
          future operations.
    * **On-Demand Message Body Fetching (Refinement):**
        * **REFINEMENT NEEDED:** Strategy for re-fetching stale bodies (e.g., based on timestamp or
          explicit user action).
   * **Other Offline Writes Not Implemented:** `moveMessage`, `sendMessage`, draft handling.
    * **Attachment Handling Not Implemented.**
    * **Local Full-Text Search Not Implemented.**

4. **Code Health and Maintenance (CODE ISSUES):**
    * **Log Spam:** Extensive diagnostic logging should be reviewed and reduced throughout the
      codebase.
    * **Lint Warning in `DefaultAccountRepository.kt`:** A persistent lint warning regarding "
      condition always true" exists around the `errorCode` derivation from
      `PersistenceResult.Failure`. Current suppressions are ineffective. (Minor issue, logic is
      sound).
   * **Deprecated WebView `setInitialScale` call in `MessageDetailScreen.kt` (FIXED):**
     A deprecated call `WebView.settings.setInitialScale()` was present and causing build
     failures on newer API levels. This was removed on 2024-07-30; `loadWithOverviewMode` and
     `useWideViewPort` are used instead.

## **III. Unified List of Work That Is Still To Be Done to Achieve the Vision (Verified 2024-07-30)
**

This list outlines the necessary steps to realize the full offline-first caching vision.

1. **Implement Comprehensive Offline Write Operations with WorkManager (High Priority):**
    * **A. Delete Message (`DefaultMessageRepository.deleteMessage`) - DONE (2024-07-30)**
    * **B. Move Message (`DefaultMessageRepository.moveMessage`)**
    * **C. Drafts & Send Message (`createDraftMessage`, `updateDraftMessage`, `sendMessage`)**

2. **Implement Attachment Handling (High Priority):**
    * **A. DB Schema for Attachments**
    * **B. API Service Updates**
    * **C. `DefaultMessageRepository.getMessageAttachments`**
    * **D. `DefaultMessageRepository.downloadAttachment`**

3. **Enhance `SyncMessageStateWorker.kt` (Ongoing with new operations):**
    * Now handles Mark Read, Star, and Delete operations.
    * **Error Handling:** Implement detailed error mapping for each operation. Differentiate between
      recoverable and non-recoverable errors.
    * **Retry Policy:** Use WorkManager's built-in retry mechanisms. Configure exponential backoff
      and max retry attempts.
    * **Input Data:** Ensure all necessary data for each operation is passed correctly via
      WorkManager `Data`.

4. **Implement Local Full-Text Search (FTS) (Medium Priority):**
    * **A. DB Schema for FTS**
    * **B. Update `AppDatabase.kt`**
    * **C. Data Population**
    * **D. `DefaultMessageRepository.searchMessages`**

5. **Refine On-Demand Message Body Fetching (Medium Priority):**
    * In `DefaultMessageRepository.getMessageWithBody`:
        * **Error Handling:** If network fetch for body fails, ensure `send(initialDomainMessage)` (
          the cached version) is robust. Log error appropriately.
        * **Stale Body Re-fetch Strategy:** Implement logic to decide when to re-fetch a body (e.g.,
          timestamp-based, user-triggered).

6. **Address API-Model Discrepancies (Ongoing/As Discovered):**
    * During implementation of other features, update API helpers and mappers if `MailApiService`
      implementations don't provide all necessary fields.

7. **Code Health and Maintenance (Ongoing):**
    * **Review and Reduce Log Spam.**
    * **Resolve/Suppress Lint Warnings:** Address any new or remaining lint warnings where
      appropriate.
    * **`ButtonPrimary` Styling:** Decide on and implement a consistent style for primary buttons.

8. **Enhance API Capabilities and Advanced Synchronization (Longer Term):**
    * **`MailApiService` Pagination:** Modify `getMessagesForFolder` to support token-based
      pagination. Update `MessageRemoteMediator`.
    * **Smart Folder Sync Strategy.**
    * **Conflict Resolution.**

## **IV. Soft Spots, Unknowns, and Research Needs (Post Code Review - 2024-07-30)**

* **Soft Spots:**
    * **Paging/RemoteMediator Error Handling:** Robustness of error handling and user feedback for
      `MessageRemoteMediator`.
    * **`SyncMessageStateWorker.kt` Concurrency & Uniqueness:** Ensuring correct WorkManager unique
      work policies for multiple modifications (e.g., deleting a message that is also being marked
      as read). Current tag `SyncMessageState_${accountId}_${messageId}` should provide per-message
      sequential execution.
    * **Database Transaction Integrity:** Verification of transaction usage for all multi-step DAO
      operations (seems okay for implemented features).

* **Unknowns (Focus on areas not deeply inspected recently):**
    * Detailed implementation nuances of `MicrosoftTokenPersistenceService.kt` and
      `GoogleTokenPersistenceService.kt`.
  * Full error handling and edge case management in `GmailApiHelper.kt` and `GraphApiHelper.kt`
    for operations *not yet implemented* (e.g., move, send).

* **Research/Investigation Needs:**
    * **WorkManager Best Practices for Complex Sync:** Advanced error handling, managing
      dependencies, detailed progress/status feedback (especially for multi-step operations like
      send message or move message which might involve multiple API calls or checks).
    * **Ktor Client Configuration for Robustness:** Review Ktor client setup for timeouts, retries,
      and network error mapping for all API calls.
    * **Database Performance with FTS:** For large mailboxes, investigate potential performance
      implications of FTS5.

This rewritten `CACHE2.md` should now accurately reflect the project's state post-refactoring and
delete implementation, providing an updated roadmap. 