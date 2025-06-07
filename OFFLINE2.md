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
        * Adding missing method overrides to interfaces (`observeMessageAttachments`).
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

* **Phase 1.B/C/D (Sync Workers & Offline Queuing): SKELETONS IMPLEMENTED, LOGIC PENDING/PARTIALLY
  ADDRESSED.**
    * The foundational classes for the sync engine (`SyncEngine`) and all related workers (
      `FolderListSyncWorker`, `FolderContentSyncWorker`, `MessageBodyDownloadWorker`,
      `AttachmentDownloadWorker`, `ActionUploadWorker`) exist and compile.
    * Basic interactions with DAOs and API services are in place.
    * However, the core synchronization logic (robust error handling, comprehensive delta sync,
      etc.) and particularly the **orchestration logic within `SyncEngine`** are not yet fully
      implemented.

**Key Technical Debt & Immediate Concerns (Revised):**

1. **Implement `SyncEngine` for Orchestration (TOP PRIORITY):**
    * **Problem:** There is no central mechanism (`SyncEngine`) to manage the order and dependencies
      of synchronization tasks. This is the root cause of issues like messages being fetched before
      their parent folders are synced, leading to empty folder displays (even if crashes are now
      mitigated).
    * **Impact:** The app cannot reliably sync data in the correct order, leading to an inconsistent
      and incomplete offline state.
    * **Next Steps:** Design and implement `SyncEngine.kt`. It must ensure `FolderListSyncWorker`
      completes for an account before `FolderContentSyncWorker` or `MessageRemoteMediator` attempts
      to process messages for that account.

2. **Incomplete Sync Worker Logic (High Priority):**
    * **Problem:** While workers compile and some have basic API interactions, they lack complete,
      robust logic for handling all edge cases, comprehensive delta synchronization (especially for
      deletions), and sophisticated error recovery.
    * **Impact:** The app cannot yet sync data reliably in the background, queue actions with full
      resilience, or provide a truly meaningful and up-to-date offline mode.
    * **Next Steps:** This remains a main body of work. Each worker's logic needs to be fully
      implemented and tested in conjunction with `SyncEngine`.

3. **Delta Sync for Deletions (High Priority):**
    * **Problem:** The current sync logic does not adequately account for messages, folders, etc.,
      that have been deleted on the server. The local database will not reflect these removals.
    * **Impact:** The local cache will become stale, showing items that no longer exist, leading to
      a confusing and broken user experience.
    * **Next Steps:** Design and implement a strategy for delta sync that explicitly handles
      deletions from the server. This is critical for data integrity.

4. **Pagination and `MessageRemoteMediator` Verification (Medium Priority):**
    * **Problem:** It remains unconfirmed if the `MessageRemoteMediator` correctly loads and
      displays *all* pages of messages when a user scrolls.
    * **Impact:** Users may not be able to scroll through their full message history.
    * **Next Steps:** After `SyncEngine` ensures folders are present, thoroughly test and verify the
      pagination mechanism.

**Original Key Assumptions (Re-evaluated):**

* The existing Room database schema is largely suitable. `FolderEntity.id` (local PK) correctly uses
  well-known remote IDs (e.g., "SENT") for Gmail standard folders. **(VALID)**
* WorkManager is the chosen framework. **(VALID)**
* Existing MailApiService implementations are functional. **(VALID)**
* UI will be made fully reactive. **(ASSUMED, NEEDS VERIFICATION)**
* Server is the ultimate source of truth. **(VALID)**
* Server APIs provide mechanisms for delta sync. **(ASSUMED, FULL IMPLEMENTATION PENDING)** This is
  critical.

## **3\. Phased Implementation Plan (Revised & Detailed)**

With the build stabilized and critical crash mitigated, the plan focuses on robust synchronization.

---

**Sprint/Phase 1.A: Fix Build & Stabilize Data Layer**

* **Status: COMPLETED.** The project is in a buildable and runnable state.

**Sprint/Phase 1.A.2: Mitigate Message Sync Crashes (June 2025)**

* **Status: COMPLETED.**
* **Objective:** Prevent `SQLiteConstraintException` during message sync.
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
    * **Status: NOT STARTED.**
  * **Objective:** Build the `SyncEngine` to manage and trigger `FolderListSyncWorker` for an
    account. Only upon its success should message-related sync (`FolderContentSyncWorker`, or
    signaling readiness for `MessageRemoteMediator`) for that account proceed.
  * **DoD:** `SyncEngine` can successfully orchestrate the full sync of folder lists for an account.
    Subsequent attempts to view a folder's content will find the `FolderEntity` present.

* **Task 1.B.2: Verify `MessageRemoteMediator` and Pagination (Post `SyncEngine` folder readiness)**
    * **Status: NOT STARTED.**
  * **Objective:** Once `SyncEngine` ensures `FolderEntity` records are present, run the application
    and confirm that `MessageRemoteMediator` correctly fetches and displays subsequent pages of
    messages.
  * **DoD:** Scrolling to the bottom of the message list triggers a network request and appends new
    messages. All messages for a folder can be loaded.

* **Task 1.B.3: Enhance `FolderListSyncWorker.kt` & `FolderContentSyncWorker.kt`**
    * **Status: PARTIAL (Basic logic + recent crash fixes).**
    * **Objective:** Implement more robust error handling, and basic delta sync capabilities (if API
      supports it simply, e.g., only fetching newer items if a timestamp is available). Full
      deletion handling is a separate major task.
    * **DoD:** Workers are more resilient and attempt basic delta updates.

---

**Sprint/Phase 1.C: Implement On-Demand and Action-Based Workers (Reliably)**

**Objective:** Allow on-demand download of full message bodies and enable a more robust offline
action queue, orchestrated by `SyncEngine`.

* **Task 1.C.1: Finalize `MessageBodyDownloadWorker.kt` (Integrated with `SyncEngine`)**
    * **Status: SKELETON IMPLEMENTED.**
    * **Objective:** Implement full logic, triggered via `SyncEngine` when a message detail is
      requested.
    * **DoD:** Requesting message detail downloads and saves full content.

* **Task 1.C.2: Finalize `ActionUploadWorker.kt` (Integrated with `SyncEngine`)**
    * **Status: SKELETON IMPLEMENTED.**
    * **Objective:** Implement complete logic for all action types, robust error/retry, managed by
      `SyncEngine`.
    * **DoD:** Offline actions are reliably queued and executed.

---

**Sprint/Phase 2: Advanced Sync Features & Offline Queue**

**Objective:** Implement comprehensive delta sync (including deletions) and refine the offline
action queue.

* **Task 2.1: Implement Delta Sync for Server-Side Deletions**
    * **Status: NOT STARTED.**
  * **Objective:** Design and implement mechanisms in relevant workers (FolderList, FolderContent)
    and `SyncEngine` to detect and apply server-side deletions to the local cache.
  * **DoD:** Deleting an email on the server eventually removes it from the local app cache.

* **Task 2.2: Refine Offline Action Queueing Mechanism**
    * **Status: NOT STARTED.** (Original task from previous plan)
    * **Objective:** Define and implement the database table and DAOs for storing pending offline
      actions if not already robust. Refactor repositories to write to this queue.
    * **DoD:** Offline actions use a persistent queue read by `ActionUploadWorker`.

---

## 4\. Next Steps & Current Build Status

### Current Build Status: SUCCESSFUL & MORE STABLE

The project **builds and compiles successfully**. Critical crashes related to message sync foreign
key constraints have been mitigated with defensive checks in `MessageRemoteMediator` and
`FolderContentSyncWorker`.

### Immediate Priorities:

1. **Implement `SyncEngine` for Sync Orchestration (CRITICAL):**
    * **The Problem:** The lack of ordered execution of sync tasks (`FolderListSyncWorker` before
      message sync) is the primary reason folders may appear empty or data may be inconsistent, even
      if crashes are avoided.
    * **What Needs to Be Done:** Design and implement `SyncEngine.kt`. This engine must ensure
      `FolderListSyncWorker` runs and completes successfully for an account *before*
      `FolderContentSyncWorker` is run for any folder in that account, and before
      `MessageRemoteMediator` attempts to load messages.
    * **Next Step:** Begin design and implementation of `SyncEngine.kt`.

2. **Verify Message Pagination (Post `SyncEngine` basic folder readiness):**
    * **The Problem:** Unconfirmed if `MessageRemoteMediator` correctly loads all pages.
    * **What Needs to Be Done:** Once `SyncEngine` ensures folders are present, thoroughly test
      pagination.
    * **Next Step:** Perform manual and automated tests for message pagination.

3. **Implement Full Logic for Sync Workers (including Deletion Handling):**
    * **The Problem:** Sync workers have basic structures but need full, robust logic, especially
      for delta sync (including deletions) and comprehensive error handling.
    * **What Needs to Be Done:** Methodically complete the business logic for each sync worker,
      integrated with and orchestrated by `SyncEngine`. Prioritize handling server-side deletions.
    * **Next Step:** After `SyncEngine` basics, enhance `FolderListSyncWorker` and
      `FolderContentSyncWorker` with full delta sync logic.

### Path to Vision (Post-Crash Mitigation)

1. **Implement `SyncEngine` Orchestration.** **(Immediate Next Step)**
2. **Verify Pagination** (once `SyncEngine` ensures folder data is present).
3. **Implement Full Sync Workers Logic** (including robust delta sync for changes and deletions).
4. **Implement Offline Actions & Queuing** (fully integrated with `SyncEngine`).
5. **Refine and Test:** Continuously test the offline experience, refine sync logic, and handle edge
   cases and errors.