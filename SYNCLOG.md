# Sync Log

This document tracks the progress, challenges, and next steps of the sync infrastructure implementation and refactoring.

---

## **Date: 2024-07-25**

### **Developer:** Gemini

### **Goal**

The primary goal of this work session was to address the large number of compilation errors stemming from a recent, major refactoring of the data and sync layers. The focus was on identifying the root causes and systematically fixing them, starting with the repository layer.

### **Summary of Work Completed**

1.  **Initial Analysis:** Began by analyzing the project state. It was clear from the initial build that the interfaces for repositories and the structure of sync jobs had fundamentally changed.

2.  **File Deletions:** Obsolete files related to the old sync status system were removed:
    *   `core-data/src/main/java/net/melisma/core_data/model/SyncStatus.kt`
    *   `core-db/src/main/java/net/melisma/core_db/converter/SyncStatusConverter.kt`

3.  **Repository Interface Alignment:** The domain repository interfaces were updated to reflect the new sync architecture.
    *   **File Changed:** `core-data/src/main/java/net/melisma/core_data/repository/FolderRepository.kt`
    *   **File Changed:** `core-data/src/main/java/net/melisma/core_data/repository/MessageRepository.kt`

4.  **`DefaultFolderRepository` Refactoring:** This repository was successfully refactored to align with its updated interface and the new `SyncJob` definitions.
    *   **File Changed:** `data/src/main/java/net/melisma/data/repository/DefaultFolderRepository.kt`
    *   **Changes:**
        *   Updated `syncFolders` to submit `SyncJob.SyncFolderList`.
        *   Updated `syncFolder` to submit `SyncJob.ForceRefreshFolder`.
        *   Corrected method signatures and dependencies.

5.  **Investigation of the New Action Queue:** A major discovery was the move from direct API calls within repositories to a persistent queue of pending actions.
    *   **Key Insight:** All user-generated actions (e.g., mark as read, delete, send) must now be persisted as a `PendingActionEntity` in the database. A `SyncJob.UploadAction` is then enqueued with the ID of the saved entity. This makes the system more robust and offline-capable.
    *   **Files Read for Context:**
        *   `core-db/src/main/java/net/melisma/core_db/entity/PendingActionEntity.kt`
        *   `core-db/src/main/java/net/melisma/core_db/dao/PendingActionDao.kt`
        *   `core-data/src/main/java/net/melisma/core_data/model/SyncJob.kt`

6.  **`DefaultMessageRepository` Refactoring (In Progress):** Began the significant task of refactoring this repository. The work is partially complete.
    *   **File Changed:** `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`

### **Challenges & Technical Debt**

*   **New `PendingAction` Workflow:** This is a positive architectural change, but it requires a significant, one-time refactoring effort across all repositories that perform write operations.
*   **Missing `MessageDraft` Mapper:** The build is failing on `toEntity` calls for `MessageDraft`. Investigation revealed that no mapper exists to convert a `MessageDraft` (domain model) into a `MessageEntity` (database entity). This needs to be created.
*   **Attachment Download Mechanism:** The old `SyncJob.DownloadAttachment` job no longer exists. A new mechanism for handling attachment downloads needs to be designed and implemented. The existing code in `DefaultMessageRepository.downloadAttachment` is now broken.
*   **Numerous Downstream Errors:** The changes in the repositories and sync jobs have caused a cascade of compilation errors in the `sync` package (`SyncController`, `SyncEngine`, and all workers) and in `DefaultThreadRepository`. These still need to be addressed.

### **Current Status**

The project is **not in a compilable state**.

The primary focus is fixing the `:data` module. `DefaultFolderRepository` is stable, but `DefaultMessageRepository` is in the middle of a major refactoring. The build output still contains many errors related to `SyncJob` usage, mappers, and the pending action queue.

### **Next Steps & Plan for Night Shift**

The immediate goal is to get the `:data` module to compile successfully.

**1. Create `MessageDraft` to `MessageEntity` Mapper:**
   *   **Task:** Create a new mapping function in `data/src/main/java/net/melisma/data/mapper/MessageMappers.kt`. This function should take a `MessageDraft` and other necessary parameters (like `accountId`, `folderId`, `syncStatus`) and return a fully populated `MessageEntity`.
   *   **File to Edit:** `data/src/main/java/net/melisma/data/mapper/MessageMappers.kt`

**2. Complete `DefaultMessageRepository.kt` Refactoring:**
   *   **Task:** This is the highest priority.
   *   Inject `PendingActionDao` into the repository.
   *   For every method that modifies data (`markMessageRead`, `starMessage`, `deleteMessage`, `moveMessage`, `sendMessage`, `createDraftMessage`, `updateDraftMessage`):
       1.  Create and populate a `PendingActionEntity`.
       2.  Use `pendingActionDao.insertAction()` to save it to the database.
       3.  Get the returned ID from the insert operation.
       4.  Submit a `SyncJob.UploadAction(pendingActionId = newActionId, accountId = ...)` to the `syncController`.
   *   Fix all `SyncJob` calls to use the correct classes from `SyncJob.kt` (e.g., `ForceRefreshFolder`, `FetchFullMessageBody`).
   *   Use the new `MessageDraft` mapper to fix the `toEntity` compilation errors.
   *   For `downloadAttachment`, temporarily comment out the `syncController.submit()` call and add a `// TODO:` comment to flag that a new implementation is required.
   *   **File to Edit:** `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`

**3. Address Downstream Errors:**
   *   **Task:** Once the repositories are stable, work through the remaining compilation errors in the following files. Read each one carefully before editing.
   *   `data/src/main/java/net/melisma/data/paging/MessageRemoteMediator.kt`
   *   `data/src/main/java/net/melisma/data/repository/DefaultThreadRepository.kt`
   *   `data/src/main/java/net/melisma/data/sync/SyncController.kt`
   *   `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
   *   All worker files in `data/src/main/java/net/melisma/data/sync/workers/`

**4. Build Continuously:**
   *   **Command:** `./gradlew build --no-daemon`
   *   Run this command after fixing each file or a small group of related files to ensure you are making progress.

Good luck!

---

## **Date: 2025-06-14**

### **Developer:** ChatGPT-o3 (Automated)

### **Goal**
Continue Phase 1 refactor: finish wiring the PendingAction-first workflow, migrate repositories to new `SyncJob` API, start eliminating legacy Worker classes, and drive the project back to a clean build.

### **Summary of Work Completed**
1. **`SyncJob` upgrade** – `UploadAction` now carries `actionType`, `entityId`, `payload`; legacy job aliases (`RefreshFolderContents`, `DownloadMessageBody`, `DownloadAttachment`) re-added for backward compatibility.
2. **`DefaultMessageRepository` fully migrated** – every mutating call now:
   • Persists a `PendingActionEntity` via new helper  
   • Submits `SyncJob.UploadAction`.
3. **`MessageDraft` mapper** – new `MessageDraft.toEntity` in `MessageDraftMapper.kt` enables draft persistence.
4. **DAO bridge methods** – added legacy helper queries to `MessageDao` plus cache-eviction query.
5. **`SyncController`** – exhaustiveness fixed; routes legacy jobs to `SyncWorkManager`.
6. **`SyncWorkManager` & `ActionUploadWorker`** – parameter order updated, null-safety fixes, MOVE_THREAD handler corrected.
7. **`DefaultFolderRepository` & `DefaultThreadRepository`** – partial fixes for deprecated helpers and map-merge logic.
8. **Gradle build** – compile error count reduced ~70 % (all new code compiles); remaining failures isolated to legacy Worker classes and SyncEngine references.

### **Challenges & Next Steps**
* **Legacy Worker Surface** – Folder/Message workers still rely on removed DAO helpers; need stub or full migration.
* **SyncEngine Cleanup** – finish swapping `FolderListSyncWorker` → `FolderSyncWorker`; fix PeriodicWork builder signature.
* **ThreadRepository Adjustments** – update DAO helper call param lists.
* **Final Build Pass** – iterate through remaining compile errors, run full `./gradlew build`.

---

## **Date: 2025-06-14 (Cont.)**

### **Developer:** ChatGPT-o3 (Automated)

### **Increment Implemented – "Phase-1-C: Data Module Green Build"**

**Goal:** Bring the `:data` module (and all compile-time dependants) back to a clean build by finishing the worker/API alignment that was left half-done last night.

### **Work Completed**
1. **Worker API Alignment**
   • `FolderContentSyncWorker` – now calls `MailApiService.getMessagesForFolder`, handles null service and `RemoteKeyEntity` signature.
   • `FolderListSyncWorker` – null-safety, UUID generation for new local IDs, explicit `java.util.UUID` import.
   • `MessageBodyDownloadWorker` – null-safety for `MailApiService` lookup.
   • `AttachmentDownloadWorker` – re-added compatibility constants `KEY_ATTACHMENT_NAME` and `KEY_RESULT_ERROR` for UI callers.
2. **Repository Fixes**
   • `DefaultFolderRepository` – fixed `combine` variance and state-merge logic; added unchecked casts with suppressions to retain Flow typing without schema rewrite.
3. **Sync Flow Compilation** – all remaining broken `import` / signature mismatches inside workers resolved.
4. **Project Build** – full `./gradlew build` now completes **SUCCESSFULLY** (warnings only about deprecated `SyncEngine`).

### **Outstanding Items / Debt**
* `SyncEngine` is still referenced across repositories and UI (deprecated).  Full migration to `SyncController` remains.
* `combine` type-safety casts in `DefaultFolderRepository` are a temporary patch – should be revisited when refactoring to `SyncController`.
* Worker logic will ultimately be subsumed by `SyncController`; current fixes are to unblock builds.

### **Next Steps**
1. **Phase-1-D** – Replace all direct `SyncEngine` injections/uses with `SyncController` equivalents.
2. Remove redundant workers & sync helpers once controller logic covers their use-cases.
3. Tighten type-safety in `DefaultFolderRepository` once Flow signature is revisited.

---

## **Date: 2025-06-15**

### **Developer:** ChatGPT-o3 (Automated)

### **Increment Implemented – "Phase-1-D: Retire SyncEngine"**

**Goal:** Remove all production-code references to the deprecated SyncEngine and wire call-sites directly to SyncController + SyncJob.

### **Key Work**
1. **Repositories**
   • `DefaultFolderRepository` – replaced SyncEngine calls with `SyncController.submit(SyncJob.*)`; removed obsolete dependency.
   • `DefaultThreadRepository` – added `PendingActionDao` + helper; all `queueAction` → persisted PendingAction + `SyncJob.UploadAction`; all folder/content refresh flows now use `SyncJob.ForceRefreshFolder`; removed SyncEngine dependency.
2. **ViewModels**
   • `MessageDetailViewModel` & `ThreadDetailViewModel` now depend on SyncController and submit `FetchFullMessageBody` jobs for silent refreshes.
3. **DI Changes** – Hilt now satisfies new constructor parameters automatically; no module edits required.
4. **Compilation** – full `./gradlew build` passes with **zero** SyncEngine references in production code (class remains deprecated for tests only).

### **Tech-Debt Left**
* `SyncEngine` class still exists but is now unused; delete once tests are migrated.
* `DefaultThreadRepository` still contains legacy threading logic comments.

### **Next Steps**
1. **Phase-1-E:** Database "nuke & pave" migration tables (MessageFolderJunction, FolderSyncStateEntity, MessageEntity schema change).
2. Remove legacy Worker duplication once SyncController internal logic covers them.

---

## **Date: 2025-06-15 (Cont.)**

### **Developer:** ChatGPT-o3 (Automated)

### **Increment Implemented – "Phase-1-E: Initial DB Nuke-and-Pave"**

**Goal:** Lay database groundwork for many-to-many model by introducing junction/state tables and bumping schema version.

### **Work Completed**
1. **Entities Added**
   • `MessageFolderJunction` – bridges messages ↔ folders.  
   • `FolderSyncStateEntity` – stores nextPageToken & lastSyncedTimestamp per folder.
2. **AppDatabase**  
   • Registered new entities, schema version bumped 15 → 16.
3. **Migration Strategy**  
   • `DatabaseModule` now calls `.fallbackToDestructiveMigration()` so upgrade to v16 wipes previous schema ("nuke & pave").
4. **Build**  
   • Project compiles successfully (`./gradlew assembleDebug`).

### **Notes / Next Steps**
* DAOs & repositories still rely on `MessageEntity.folderId`. Next increment will remove that column and refactor queries to use the junction table.  
* Need DAO for new entities and updates to FolderContentSyncWorker to populate junction rows.

--- 