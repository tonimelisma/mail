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

## **2\. Current State, Technical Debt & Key Assumptions (Revised October 2023 - Updated by AI
Assistant)**

**Current Implemented State (Summary):**

* **Phase 0 (Foundation & Critical Bug Fixes):**
    * MessageBodyEntity persistence bug: **ADDRESSED**.
    * Room Entities enhanced with sync metadata (SyncStatus, timestamps, etc.): **COMPLETED**.
    * Account/AccountEntity refactor (displayName/emailAddress): **IMPLEMENTED** (as per original
      doc).
* **Sprint/Phase 1.A (Fix `MailApiService` Pagination & `MessageRemoteMediator` - Code
  Implementation):**
    * `PagedMessagesResponse.kt` in `core-data`: **CREATED and DEFINED.**
    * `MailApiService.kt` interface in `core-data`: `getMessagesForFolder` signature **UPDATED** for
      pagination.
    * `GmailApiHelper.kt` in `backend-google`: `getMessagesForFolder` **REFACTORED** for pagination
      using Ktor (removed GMS batching for this method as a shortcut to resolve type issues),
      `DEFAULT_MAX_RESULTS` added. DI constructor **UPDATED**.
    * `GraphApiHelper.kt` in `backend-microsoft`: `getMessagesForFolder` **REFACTORED** for
      pagination.
    * `RemoteKeyEntity.kt` and `RemoteKeyDao.kt` in `core-db`: **CREATED and ADDED** to
      `AppDatabase.kt` (DB version incremented, migration added).
    * `MessageRemoteMediator.kt` in `data`: **REFACTORED** to use `RemoteKeyDao` and new pagination
      logic.
    * `BackendGoogleModule.kt`: DI providers for `CoroutineDispatcher` and `Gmail?` service (
      nullable placeholder) **ADDED**. `GmailApiHelper` provider **UPDATED** with new dependencies.
      `GoogleAuthManager` correctly injected.
    * `ErrorDetails.kt` in `core-data`: **UPDATED** with `isNeedsReAuth` and `isConnectivityIssue`
      parameters, resolving `GoogleErrorMapper.kt` issues.
    * `GmailApiHelperTest.kt`: **UPDATED** to provide new mocked dependencies for `GmailApiHelper`
      constructor.
* **Build System & Core Libraries Fixes (Partial - As of Last AI Build Attempt):**
    * **`core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.kt`**: Removed
      unused imports for `MessageBodyTuple` and `MessageIdWithBody`.
    * **`backend-google/build.gradle.kts`**: Added
      `implementation("com.google.apis:google-api-services-gmail:v1-rev20220404-2.0.0")`.
    * **`core-db/src/main/java/net/melisma/core_db/dao/FolderDao.kt`**: Added missing `SyncStatus`
      import and corrected SQL query.
    * **`core-data/src/main/AndroidManifest.xml`**: Created missing file, added network permission.
    * **IMPORTANT NOTE:** The project **IS NOT CURRENTLY BUILDING**. The last build attempt failed
      with KSP errors in the `backend-microsoft` module:
      `InjectProcessingStep was unable to process 'GraphApiHelper(...)' because 'CoroutineDispatcher' could not be resolved.`
      This is despite `:core-data` providing the qualified dispatcher and `backend-microsoft`
      depending on it.
* **Phase 1 (Decoupling Read Path & Initial Sync Workers - Original State):**
    * Repository Read Methods (DB-Centric): **PARTIALLY IMPLEMENTED**.
        * **CRITICAL ISSUE (PENDING VERIFICATION AFTER SPRINT 1.A FIXES):**
          `DefaultMessageRepository`'s usage of `MessageRemoteMediator` previously resulted in only
          the first page loading. This should be re-evaluated once the build is successful.
    * `SyncEngine` and Initial SyncWorkers: Skeletons exist, **NEED FULL IMPLEMENTATION.**
* **Phase 2 (Decoupling Write Path - Queuing Offline Actions - Original State):** **NOT STARTED.**
* **Phase 3 (Building a Robust Sync Engine & Advanced Features - Original State):** **NOT STARTED.**

**Key Technical Debt & Immediate Concerns (Updated):**

1. **KSP Resolution for Qualified `CoroutineDispatcher` (TOP PRIORITY BUILD BLOCKER):**
    * **Problem:** KSP in `backend-microsoft` module fails to resolve
      `@Dispatcher(MailDispatchers.IO) CoroutineDispatcher` provided by `core-data` module for
      `GraphApiHelper` constructor.
    * **Impact:** Project cannot build.
    * **Root Cause:** Likely a build tooling issue (KSP/Hilt caching or inter-module processing) as
      definitions and dependencies appear correct.
2. **`MessageRemoteMediator` Full Offline Incapability (Addressed by Sprint 1.A code changes,
   pending successful build & testing):**
    * **Problem:** Previously only loaded the first page.
    * **Impact:** Limited offline access.
    * **Resolution Attempted:** Sprint 1.A refactored `MessageRemoteMediator` and `MailApiService`
      implementations to support proper pagination.
3. **Incomplete `MailApiService` Pagination & Integration (Addressed by Sprint 1.A code changes):**
    * `getMessagesForFolder` in both `GmailApiHelper.kt` and `GraphApiHelper.kt` updated for
      pagination.
    * **Shortcut/Change for `GmailApiHelper.kt`**: The previous GMS SDK batching logic within
      `getMessagesForFolder` was removed. The method now uses Ktor to list message IDs and then
      Ktor-based `fetchRawGmailMessage` for individual message details. This resolved compilation
      issues with GMS batching types and simplified the pagination logic for this specific method.
      Other methods in `GmailApiHelper` might still use `gmailService`.
4. **Dependency Injection (Hilt) Misconfiguration for `GmailApiHelper` (Addressed by Sprint 1.A code
   changes):**
    * `BackendGoogleModule.kt` now provides `ioDispatcher`, a nullable `gmailService`, and
      `GoogleAuthManager` to `GmailApiHelper`.
5. **Unresolved Types & Constants in `GmailApiHelper.kt` (Addressed by Sprint 1.A code changes):**
    * The problematic GMS batch processing logic in `getMessagesForFolder` was removed, thus
      resolving related type issues (e.g., `JsonBatchCallback`, `MessageModel` alias).
      `DEFAULT_MAX_RESULTS` constant was added.
6. **Unresolved Parameters in `GoogleErrorMapper.kt` (Addressed by Sprint 1.A code changes):**
    * `ErrorDetails.kt` (in `core-data`) was updated to include `isNeedsReAuth` and
      `isConnectivityIssue`, resolving the constructor/parameter issues when `GoogleErrorMapper`
      creates `ErrorDetails` instances.
7. **Incomplete Sync Workers:** All sync workers still require full implementation.
8. **Basic `SyncEngine`:** Still requires significant work.
9. **Delta Sync for Deletions:** Not implemented.

**Original Key Assumptions (Still Mostly Valid, with Caveats):**

* The existing Room database schema is largely suitable and was enhanced with sync metadata. **(
  VALID)**
* WorkManager is the chosen framework. **(VALID)**
* Existing MailApiService implementations are functional for backend communication (now enhanced for
  pagination). **(VALID - Pagination implemented)**
* UI will be made fully reactive. **(ASSUMED, NEEDS VERIFICATION)**
* Server is the ultimate source of truth. **(VALID)**
* Server APIs provide mechanisms for delta sync (needs to be leveraged). **(ASSUMED, NEEDS
  IMPLEMENTATION)**

## **3\. Phased Implementation Plan (Revised & Detailed - Strategy 1: Enhanced Current Path)**

This plan adopts the **"Enhanced Current Path"** strategy, focusing on making
`MessageRemoteMediator` fully functional and building out the `SyncEngine` and workers as
envisioned.

**Overall Goal:** Achieve reliable, paginated fetching of all messages for a folder via
`MessageRemoteMediator`, and implement foundational sync workers and action queuing.

---

**Sprint/Phase 1.A: Fix `MailApiService` Pagination & `MessageRemoteMediator` (The Critical Hurdle)
**

**Objective:** Enable `MessageRemoteMediator` to load *all* messages for a folder with proper
pagination.

* **Task 1.A.1: Define `PagedMessagesResponse` in `core-data`**
    * **Status: COMPLETED.**
    * **File:** `core-data/src/main/java/net/melisma/core_data/model/PagedMessagesResponse.kt`
    * **Content:**
      ```kotlin
      package net.melisma.core_data.model // Or appropriate sub-package

      data class PagedMessagesResponse(
          val messages: List<Message>,
          val nextPageToken: String? // Token for the next page, null if no more pages
      )
      ```
    * **DoD:** File created, data class defined.

* **Task 1.A.2: Update `MailApiService` Interface**
    * **Status: COMPLETED.**
    * **File:** `core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.kt`
    * **Change:** Modified `getMessagesForFolder` signature:
      ```kotlin
      suspend fun getMessagesForFolder(
          folderId: String,
          activity: android.app.Activity? = null,
          maxResults: Int? = null, 
          pageToken: String? = null // New parameter for pagination
      ): Result<PagedMessagesResponse> // Return type changed
      ```
    * **DoD:** Interface updated.

* **Task 1.A.3: Implement Pagination in `GmailApiHelper.kt`**
    * **Status: COMPLETED.**
    * **File:** `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
    * **Function:** `getMessagesForFolder`
    * **Logic:**
        1. Function uses `pageToken: String?` parameter.
        2. **Shortcut/Change:** Uses Ktor for listing message IDs and then Ktor-based
           `fetchRawGmailMessage` for individual message details. The previous GMS SDK batching
           logic and its unresolved types/constants (`JsonBatchCallback`, `MessageModel`,
           `HttpHeaders`, `GoogleJsonError`, `toDomainMessage()`) for *this specific method* were
           removed to resolve compilation blockers and simplify pagination integration. Other
           methods in `GmailApiHelper` might still use the `gmailService` instance.
        3. Maps API response messages to `List<Message>`.
        4. Extracts `nextPageToken` from the API list response.
        5. Returns `Result.success(PagedMessagesResponse(mappedMessages, extractedNextPageToken))`.
        6. Handles API errors.
        7. Defined `DEFAULT_MAX_RESULTS` in companion object.
    * **DoD:** `getMessagesForFolder` correctly uses `pageToken`, returns `PagedMessagesResponse`
      with Ktor.

* **Task 1.A.4: Implement Pagination in `GraphApiHelper.kt` (Microsoft)**
    * **Status: COMPLETED.**
    * **File:** `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`
    * **Function:** `getMessagesForFolder`
    * **Logic:**
        1. Accepts `pageToken: String?` (which is the full `@odata.nextLink` from a previous
           response).
        2. If `pageToken` (nextLink) is provided, the request is made to this full URL. If null, it
           constructs the initial URL.
        3. Query parameters (`$top`, `$select`) are added only for the initial request (when
           `pageToken` is null).
        4. Parses the response, extracts messages and the new `@odata.nextLink`.
        5. The new `@odata.nextLink` is used as the `nextPageToken` for `PagedMessagesResponse`.
        6. Maps Graph API messages to `List<Message>`.
        7. Returns `Result.success(PagedMessagesResponse(mappedMessages, newNextLink))`.
    * **DoD:** `getMessagesForFolder` implemented, uses `pageToken` (nextLink), returns
      `PagedMessagesResponse`.

* **Task 1.A.5: Create `RemoteKeys` Entity and DAO**
    * **Status: COMPLETED.**
    * **Directory:** `core-db/src/main/java/net/melisma/core_db/entity/` and
      `core-db/src/main/java/net/melisma/core_db/dao/`
    * **Entity (`RemoteKeyEntity.kt`):** Defined as specified.
    * **DAO (`RemoteKeyDao.kt`):** Defined as specified.
    * Added to `AppDatabase.kt`, DB version incremented to 11, migration `MIGRATION_10_11` added.
    * **DoD:** Entity, DAO created, added to `AppDatabase`.

* **Task 1.A.6: Refactor `MessageRemoteMediator.kt`**
    * **Status: COMPLETED.**
    * **File:** `data/src/main/java/net/melisma/data/paging/MessageRemoteMediator.kt`
    * **Inject:** `AppDatabase`, `MailApiService`, `NetworkMonitor`, `CoroutineDispatcher`. Removed
      `FolderDao`, `AccountDao`.
    * **`initialize()`:** Returns `InitializeAction.LAUNCH_INITIAL_REFRESH`.
    * **`load()` method:** Implemented as specified for `REFRESH` and `APPEND` using `RemoteKeyDao`,
      `mailApiService.getMessagesForFolder`, and `state.config.pageSize`. `PREPEND` returns success
      with `endOfPaginationReached = true`.
    * **DoD:** `MessageRemoteMediator` correctly pages using `RemoteKeyEntity`.

* **Task 1.A.7: Fix DI for `GmailApiHelper` and `GoogleErrorMapper` (Build Fix Block)**
    * **Status: PARTIALLY COMPLETED (New Blocker Identified).**
    * **File:** `backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt`
        * Provider for `CoroutineDispatcher` (non-qualified `Dispatchers.IO`): **COMPLETED.**
        * Provider for `com.google.api.services.gmail.Gmail?` (nullable placeholder): **COMPLETED.**
        * `provideGmailApiHelper` updated with all new dependencies including `GoogleAuthManager`: *
          *COMPLETED.**
    * **File:** `core-data/src/main/java/net/melisma/core_data/model/ErrorDetails.kt`
        * `ErrorDetails` data class updated with `isNeedsReAuth` and `isConnectivityIssue`
          parameters. This resolved issues in `GoogleErrorMapper.kt`. **COMPLETED.**
    * **File:** `backend-google/src/test/java/net/melisma/backend_google/GmailApiHelperTest.kt`
        * Updated to provide new mocked dependencies to `GmailApiHelper` constructor. **COMPLETED.**
    * **NEW BUILD BLOCKER:** KSP error in `backend-microsoft` module:
      `InjectProcessingStep was unable to process 'GraphApiHelper(...)' because 'CoroutineDispatcher' could not be resolved.`
      This is for the `@Dispatcher(MailDispatchers.IO) CoroutineDispatcher`.
    * **DoD:** Original `GmailApiHelper` and `GoogleErrorMapper` DI issues are resolved.
      `GmailApiHelperTest.kt` updated. Application still **DOES NOT BUILD** due to new KSP error in
      `backend-microsoft`.

---

**Sprint/Phase 1.B: Implement Core Sync Workers (Folder List & Basic Content)**

**Objective:** Get foundational data synced without user interaction.

* **Task 1.B.1: Implement `FolderListSyncWorker.kt`**
    * **File:** `data/src/main/java/net/melisma/data/sync/workers/FolderListSyncWorker.kt`
    * **InputData:** `accountId: String`.
    * **Logic:**
        1. Retrieve `accountId`. Get `MailApiService`.
        2. Call `mailService.getMailFolders(accountId = accountId, activity = null)`.
        3. On success (`List<MailFolder>`): Map to `List<FolderEntity>`, set
           `syncStatus = SyncStatus.SYNCED`, `lastSuccessfulSyncTimestamp`.
           `folderDao.insertOrUpdateFolders(folderEntities)`. Update
           `AccountEntity.lastFolderListSyncTimestamp`. Return `Result.success()`.
        4. On failure: Log, update `AccountEntity.lastFolderListSyncError`. Return
           `Result.retry()/failure()`.
    * **DoD:** Worker fetches and stores folders.

* **Task 1.B.2: Implement `SyncEngine.kt` (Basic Folder Sync Scheduling)**
    * **File:** `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
    * **Inject:** `WorkManager`.
    * **Method:** `fun requestFolderListSync(accountId: String, force: Boolean = false)`: Enqueues
      `FolderListSyncWorker` (unique one-time work).
    * **Method:** `fun schedulePeriodicFolderSync(accountId: String)`: Enqueues
      `FolderListSyncWorker` (unique periodic work).
    * **DoD:** `SyncEngine` can schedule folder syncs.

---

**Sprint/Phase 1.C: Message Body & Attachment Download Workers**

**Objective:** Allow on-demand download of message bodies and attachments.

* **Task 1.C.1: Implement `MessageBodyDownloadWorker.kt`**
    * **File:** `data/src/main/java/net/melisma/data/sync/workers/MessageBodyDownloadWorker.kt`
    * **InputData:** `accountId: String`, `messageId: String`.
    * **Logic:** Get params, `MailApiService`. Optimistically update `MessageBodyEntity` to
      `DOWNLOADING`. Call `mailService.getMessageContent(messageId)`. On success, update
      `MessageBodyEntity` (content, `SYNCED`). On failure, update status to `ERROR`.
    * **DoD:** Worker downloads and saves message body.

* **Task 1.C.2: Implement `AttachmentDownloadWorker.kt`**
    * **File:** `data/src/main/java/net/melisma/data/sync/workers/AttachmentDownloadWorker.kt`
    * **InputData:** `accountId: String`, `messageId: String`, `attachmentId: String` (local DB ID),
      `attachmentRemoteId: String` (API ID), `attachmentName: String`.
    * **Logic:** Get params, `MailApiService`. Optimistically update `AttachmentEntity`. Call
      `mailService.downloadAttachment(messageId, attachmentRemoteId)`. On success (`ByteArray`),
      save to file, update `AttachmentEntity` (`localFilePath`, `isDownloaded = true`, `SYNCED`). On
      failure, update status to `ERROR`.
    * **DoD:** Worker downloads and saves attachment.

* **Task 1.C.3: `SyncEngine` Methods for On-Demand Downloads**
    * **File:** `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
    * **Method:** `fun requestMessageBodyDownload(accountId: String, messageId: String)`: Enqueues
      `MessageBodyDownloadWorker`.
    * **Method:**
      `fun requestAttachmentDownload(accountId: String, messageId: String, attachmentId: String, attachmentRemoteId: String, attachmentName: String)`:
      Enqueues `AttachmentDownloadWorker`.
    * **DoD:** `SyncEngine` can trigger these downloads.

---
**Soft Spots & Research Areas (Reiteration for this plan):**

* **`MessageRemoteMediator` Full Pagination:** Conditional clearing on `REFRESH` vs. merging.
  Reliable page token management.
* **Delta Sync (especially Deletions):** How to efficiently detect and apply server-side deletions.
* **`GmailApiHelper.getMessagesForFolder`:** Reconciling its current Java SDK batching approach with
  the need to support simple `pageToken` input and `PagedMessagesResponse` output for
  `RemoteMediator`. It might be simpler to have a separate, Ktor-based paged list method if the
  batching one is too complex to adapt quickly.
* **Error Handling & Retries in Workers:** Robustly distinguishing error types.
* **`SyncEngine` Orchestration:** Complex interactions, priorities, and overall state.
* **Conflict Resolution:** For now, the plan is mostly optimistic local updates + server overwrite.
  True conflict resolution (e.g., user moves email offline, server also moves it elsewhere) is not
  deeply covered yet.

This detailed plan prioritizes getting the read path (full folder content) working correctly,
followed by reliable offline action processing. Subsequent phases would build upon this to implement
full delta sync, cache management, and more advanced features.