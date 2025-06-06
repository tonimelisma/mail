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

## **2\. Current State, Technical Debt & Key Assumptions (Revised November 2023 - Updated by AI
Assistant)**

**Current Implemented State (Summary):**

* **Phase 0 (Foundation & Critical Bug Fixes):**
    * MessageBodyEntity persistence bug: **ADDRESSED**.
    * Room Entities enhanced with sync metadata (SyncStatus, timestamps, etc.): **COMPLETED**.
  * Account/AccountEntity refactor (displayName/emailAddress): **IMPLEMENTED**.
* **Build System & Core Libraries Fixes (Largely Addressed):**
    * **KSP Hilt `CoroutineDispatcher` Resolution:** **FIXED**. The original build blocker in the
      `backend-microsoft` module was resolved by explicitly including the `DispatchersModule` from
      `core-data` in the `BackendMicrosoftModule` via
      `@Module(includes = [DispatchersModule::class])`.
    * **Data Layer Compilation Cascade:** **LARGELY FIXED**. A significant number of compilation
      errors in
      the `:data` module (`DefaultMessageRepository`, `DefaultFolderRepository`, various Workers)
      and `:core-db`
      module (various DAOs) were resolved. This involved:
        * Adding missing methods to DAO interfaces (`FolderDao`, `MessageDao`, `AttachmentDao`,
          `MessageBodyDao`, `AccountDao`).
        * Correcting mismatched method signatures between repositories and their interfaces.
        * Fixing unresolved references by aligning method calls with their new definitions.
        * Updating data model classes (`MessageDraft`, `Attachment`, `Message`, `MessageEntity`,
          `FolderEntity`, `MessageBodyEntity`, `EmailAddress`)
          to include missing properties or align existing ones.
        * Adding new mapper files (`AttachmentMapper.kt`) and correcting existing ones.
        * Refactoring Worker classes to correctly parse action payloads and interact with DAOs and
          API services.
        * **OUTSTANDING ISSUE:** Persistent, unusual compiler errors in
          `data/src/main/java/net/melisma/data/mapper/MessageMappers.kt` ("No value passed for
          parameter 'id'/'body'" on the function signature line) despite the constructor call
          appearing correct.
    * **Room Foreign Key Constraint:** **FIXED**. A KSP error in `:core-db` related to a missing
      unique index for a foreign key in `MessageBodyEntity` was resolved by updating the entity's
      schema definition.
    * **API Helper and Worker Implementation Progress (Partial):**
        * `GraphApiHelper.kt`: Initial `accountId`/`folderId` constructor fix for `Message.fromApi`,
          `ErrorDetails` import/usage corrected.
        * `GmailApiHelper.kt`: Linting fix applied. **STILL A PRIMARY BUILD BLOCKER** due to
          outdated constructor calls for `Message` and `Attachment`.
        * `DefaultMessageRepository.kt`: Resolved issues with `MessageEntity.toDomainModel()`
          arguments, `PagedMessagesResponse` usage, `EmailAddress` import and mapping, and
          `SyncStatus` enum usage.
        * `ActionUploadWorker.kt`: Corrected `MailApiServiceSelector` usage, `moveMessage`
          parameters, and switched to `kotlin.Result`. The `markMessageAsRead` call is **temporarily
          commented out** due to an unresolved reference error specific to it.
        * `AttachmentDownloadWorker.kt`: Refactored DAO interactions to use a fetch-update-save
          pattern with `AttachmentEntity` and `AttachmentDao.updateAttachment()`.
        * `FolderContentSyncWorker.kt`: Corrected `nextPageToken` field usage from `FolderEntity`
          and implemented saving of the new page token via `FolderDao.updatePagingTokens()`.
        * `FolderListSyncWorker.kt`: Fixed `workerParams` access, `MailApiServiceSelector` usage,
          `withTransaction` import, lambda parameter typing for `MailFolder.toEntity()`, and
          `FolderDao/AccountDao` method calls.
        * `MessageBodyDownloadWorker.kt`: Refactored `MessageBodyDao` interactions to
          fetch-update/create-insert `MessageBodyEntity`, corrected
          `MailApiService.getMessageContent()` call, and adjusted `Message` result handling.
* **Phase 1 (Decoupling Read Path & Initial Sync Workers - Original State):**
    * Repository Read Methods (DB-Centric): **PARTIALLY IMPLEMENTED**.
        * **CRITICAL ISSUE (PENDING VERIFICATION AFTER SPRINT 1.A FIXES):**
          `DefaultMessageRepository`'s usage of `MessageRemoteMediator` previously resulted in only
          the first page loading. This should be re-evaluated once the build is successful.
  * `SyncEngine` and Initial SyncWorkers: Skeletons exist, significant progress made in making them
    compile, but **NEED FULL LOGIC IMPLEMENTATION.**
* **Phase 2 (Decoupling Write Path - Queuing Offline Actions - Original State):** **NOT STARTED.**
* **Phase 3 (Building a Robust Sync Engine & Advanced Features - Original State):** **NOT STARTED.**

**Key Technical Debt & Immediate Concerns (Updated):**

1. **`GmailApiHelper.kt` Constructor Calls (TOP PRIORITY BUILD BLOCKER):**
    * **Problem:** The `GmailApiHelper.kt` in the `backend-google` module is not updated to use the
      current constructors of `Message` and `Attachment` from `core-data`, which now require
      additional parameters (e.g., `accountId`, `folderId`).
    * **Impact:** Project cannot build if this file is included in the build process.
    * **Root Cause:** Incomplete refactoring.
2. **Anomalous Compiler Errors in `MessageMappers.kt`:**
    * **Problem:** Persistent compiler errors ("No value passed for parameter 'id'/'body'") on the
      function signature line of `MessageEntity.toDomainModel()` in `MessageMappers.kt`. The
      constructor call within the function appears correct.
    * **Impact:** This is currently preventing a successful build of the `:data` module.
    * **Root Cause:** Unknown; potentially a subtle compiler issue, build configuration problem, or
      a very opaque coding error.
3. **`ActionUploadWorker.kt` - `markMessageAsRead` issue:**
    * **Problem:** The call to `mailService.markMessageAsRead()` is unresolved despite a matching
      signature in `MailApiService`. It is currently commented out.
    * **Impact:** "Mark as read" action will not work via this worker until resolved.
4. **Incomplete `MailApiService` Pagination & Integration (Partially Addressed, Blocked by Build):**
    * The `getMessagesForFolder` signature has been updated in the interface and
      `GraphApiHelper.kt`. Full verification of `GmailApiHelper.kt`'s implementation is pending a
      successful build.
5. **Incomplete Sync Workers & `SyncEngine`:** Foundational work and compilation fixes are done for
   many workers, but the core logic for all workers and the orchestration logic in `SyncEngine` is
   not implemented. This is the main next step after the build is fixed.
6. **Delta Sync for Deletions:** Not implemented. This is a critical feature for a good offline
   experience.

**Original Key Assumptions (Still Mostly Valid, with Caveats):**

* The existing Room database schema is largely suitable and was enhanced with sync metadata. **(
  VALID)**
* WorkManager is the chosen framework. **(VALID)**
* Existing MailApiService implementations are functional for backend communication (now enhanced for
  pagination). **(PARTIALLY VALID - Blocked by `GmailApiHelper.kt` compilation
  and `MessageMappers.kt` mystery)**
* UI will be made fully reactive. **(ASSUMED, NEEDS VERIFICATION)**
* Server is the ultimate source of truth. **(VALID)**
* Server APIs provide mechanisms for delta sync (needs to be leveraged). **(ASSUMED, NEEDS
  IMPLEMENTATION)**

## **3\. Phased Implementation Plan (Revised & Detailed)**

This plan focuses on fixing the build and then continuing with the phased implementation.

---

**Sprint/Phase 1.A: Fix Build & Stabilize Data Layer (The Critical Hurdle)**

**Objective:** Get the project into a buildable and runnable state to enable further development.

* **Task 1.A.1: Fix KSP/Hilt `CoroutineDispatcher` error in `backend-microsoft`**
    * **Status: COMPLETED.**
  * **Resolution:** Explicitly included `DispatchersModule` in `BackendMicrosoftModule`.

* **Task 1.A.2: Resolve Compilation Cascade in `:data` and `:core-db`**
    * **Status: LARGELY COMPLETED.** Significant progress in `DefaultMessageRepository`,
      `DefaultFolderRepository`, DAOs, and Worker classes.
    * **Remaining:** Resolve `MessageMappers.kt` compiler errors.

* **Task 1.A.3: Fix Room Foreign Key KSP Error**
    * **Status: COMPLETED.**
  * **Resolution:** Corrected the foreign key reference in `MessageBodyEntity`.

* **Task 1.A.4: Fix Compilation Errors in `GmailApiHelper.kt`**
    * **Status: BLOCKED (Primary Blocker for Full Project Build).**
    * **Objective:** Update `GmailApiHelper` to correctly use the updated `Message` and `Attachment`
      data models.

* **Task 1.A.5: Resolve `markMessageAsRead` in `ActionUploadWorker.kt`**
    * **Status: PENDING.** The call is currently commented out.
    * **Objective:** Investigate and fix the "Unresolved reference" error.

* **Task 1.A.6: Verify `MessageRemoteMediator` and Pagination**
    * **Status: NOT STARTED (Blocked by Build).**
    * **Objective:** Once the application builds, verify `MessageRemoteMediator` pages correctly.

---

**Sprint/Phase 1.B: Implement Core Sync Workers (Folder List & Basic Content)**

**Objective:** Get foundational data synced without user interaction.

* **Task 1.B.1: Finalize `FolderListSyncWorker.kt`**
    * **Status: SIGNIFICANT PROGRESS MADE (Compiles).** Needs full logic implementation and testing.

* **Task 1.B.2: Finalize `FolderContentSyncWorker.kt`**
    * **Status: SIGNIFICANT PROGRESS MADE (Compiles).** Needs full logic implementation and testing.

* **Task 1.B.3: Implement `SyncEngine.kt` (Basic Folder & Content Sync Scheduling)**
    * **Status: NOT STARTED.**
    * **DoD:** `SyncEngine` can schedule folder list and folder content syncs.

---

**Sprint/Phase 1.C: Message Body & Attachment Download Workers**

**Objective:** Allow on-demand download of message bodies and attachments.

* **Task 1.C.1: Finalize `MessageBodyDownloadWorker.kt`**
    * **Status: SIGNIFICANT PROGRESS MADE (Compiles).** Needs full logic implementation and testing.

* **Task 1.C.2: Finalize `AttachmentDownloadWorker.kt`**
    * **Status: SIGNIFICANT PROGRESS MADE (Compiles).** Needs full logic implementation and testing.

* **Task 1.C.3: `SyncEngine` Methods for On-Demand Downloads**
    * **Status: NOT STARTED.**
    * **DoD:** `SyncEngine` can trigger these downloads.

---

**Sprint/Phase 1.D: Action Upload Worker & Offline Queuing Foundation**

**Objective:** Enable basic offline actions to be queued and synced.

* **Task 1.D.1: Finalize `ActionUploadWorker.kt`**
    * **Status: SIGNIFICANT PROGRESS MADE (Compiles, `markMessageAsRead` pending).** Needs full
      logic implementation for all actions and testing.

* **Task 1.D.2: Design and Implement Offline Action Queueing Mechanism**
    * **Status: NOT STARTED.**
    * **Details:** Define how actions are stored locally (e.g., separate Room table) and how
      `ActionUploadWorker` consumes them.

* **Task 1.D.3: `SyncEngine` Integration for Action Uploads**
    * **Status: NOT STARTED.**
    * **DoD:** `SyncEngine` can trigger action uploads.

---

## 4\. Next Steps & Current Build Status

### Current Build Status: FAILED

The project currently **does not build** if all modules are included. The primary blocker for a full
project build is `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`.
However, the immediate blocker for the current set of changes in the `:data` module is the errors in
`MessageMappers.kt`.

**Errors in `:data` module:**

* `MessageMappers.kt:50:9 No value passed for parameter 'id'.`
* `MessageMappers.kt:50:9 No value passed for parameter 'body'.`

**Errors in `backend-google` module (if compiled):**

* Multiple "No value passed for parameter" errors when creating `Message` and `Attachment` objects (
  e.g., `accountId`, `folderId`, `messageId`, `localUri`).

### Immediate Priorities:

1. **Resolve `MessageMappers.kt` Errors:**
    * **The Problem:** The compiler reports missing parameters for the `Message` constructor on the
      `MessageEntity.toDomainModel()` function signature line, despite the constructor call within
      the function appearing correct.
    * **What Needs to Be Done:** This requires a deeper investigation. Since direct fixes to the
      constructor call and even using fully qualified names haven't resolved it, the issue might be
      more subtle. Possibilities include:
        * An obscure interaction with other mappers or extension functions.
        * A problem in the `Message.kt` data class itself that's manifesting indirectly here.
        * Build configuration or compiler environment issues (though less likely given other files
          compile).
    * **Next Step:** Systematically re-examine `Message.kt` for any unusual constructor logic,
      `init` blocks, or default parameter interactions. Then, analyze the usage of
      `MessageEntity.toDomainModel()` throughout the `:data` module to see if any call site could be
      providing misleading type information.
2. **Uncomment and Fix `markMessageAsRead` in `ActionUploadWorker.kt`:**
    * Once the `:data` module builds, uncomment the
      `mailService.markMessageAsRead(entityId, isRead)` line and address the "Unresolved reference"
      error. This might involve checking type inference again or looking for subtle differences in
      that specific call compared to other `mailService` calls.
3. **Fix `GmailApiHelper.kt` (Primary Project Build Blocker):**
    * **The Problem:** The `mapGmailMessageToMessage` and other methods inside `GmailApiHelper.kt`
      are attempting to create instances of `net.melisma.core_data.model.Message` and
      `net.melisma.core_data.model.Attachment` using outdated constructors.
    * **What Needs to Be Done:** Update all `Message` and `Attachment` instantiation points in
      `GmailApiHelper.kt` to provide the new required parameters (e.g., `accountId`, `folderId` for
      `Message`; `messageId`, `accountId`, `downloadStatus` for `Attachment`). Values need to be
      sourced from available data (e.g., `accountId` from method scope) or sensible defaults.

### Path to Vision (Post-Build Fixes)

1. **Verify Pagination:** Run the app and test `MessageRemoteMediator`.
2. **Implement Sync Workers Logic:** Complete the actual synchronization logic within all the worker
   classes.
3. **Implement `SyncEngine` Orchestration:** Build out the `SyncEngine` to manage and trigger sync
   tasks effectively.
4. **Implement Offline Actions & Queuing:** Develop the system for storing and syncing user actions
   performed offline.
5. **Refine and Test:** Continuously test the offline experience, refine sync logic, and handle edge
   cases and errors.
6. **Address Delta Sync for Deletions.**