# **Melisma Mail - Remediation Plan, Phase 2 (FIX2.md)**

Version: 1.0  
Date: July 3, 2025  
Author: Gemini

## **1. Executive Summary**

This document outlines the next phase of work required to bring the Melisma Mail application to a production-ready state. While the work in `FIX.md` stabilized the core sync engine and fixed critical show-stopper bugs, significant technical debt and architectural flaws remain.

The two primary goals of this plan are:
1.  **Resolve the Critical Build Blocker:** The persistent KSP error in the `:core-db` module renders the application unbuildable and must be the top priority.
2.  **Fix the Data Model:** The application's data model for messages and folders is fundamentally flawed, containing conflicting one-to-many and many-to-many implementations. This must be corrected to prevent data corruption and support modern email features properly.

---

## **2. Priority 1: Unblock The Build**

### **BLOCKER 1: Resolve KSP `[MissingType]` Error**

**Problem:** The application is unbuildable due to a persistent KSP error: `[MissingType]: Element 'net.melisma.core_db.AppDatabase' references a type that is not present`. All attempts to fix this by correcting entities, DAOs, and TypeConverters have failed.

**Plan:**
This is a deep, unconventional error likely stemming from a complex interaction between dependencies.
1.  **Dependency Audit:** Systematically review the versions of all Gradle plugins and libraries, especially `KSP`, `Room`, and `Hilt`. Look for known incompatibilities. Consider upgrading or downgrading them one by one.
2.  **Minimal Reproducible Example:** Attempt to create a new, minimal project with only the `:core-db` module and its dependencies. Incrementally add entities and DAOs until the error reappears. This is the most reliable way to isolate the exact cause.
3.  **Gradle Deep Dive:** Run the build with `--stacktrace` and `--scan` to get more detailed output that might reveal a hidden dependency conflict or a misconfiguration in the build process.

---

## **3. Priority 2: Comprehensive Data Model & Architecture Fixes**

### **ARCH-1: Finalize the Message-Folder Many-to-Many Model**

**Problem:** The user correctly identified that the app's data model is confused. Gmail uses labels (a message can be in multiple folders/have multiple labels), but the code contains remnants of a one-to-many model (`folderId` on a message). This was partially fixed by introducing the `MessageFolderJunction` table, but the implementation is incomplete and inconsistent.

**Analysis of Gaps:**
*   **DAO Layer:** `MessageDao` still contains methods like `updateFolderIdForMessages` and `updateFolderId`, which are based on the incorrect one-to-many model. These methods directly modify a column that should not exist.
*   **Repository Layer:** The `moveThread` and `moveMessage` methods in the repositories currently perform a "replace" operation, which works for moving a message from one folder to another but doesn't support the full range of label operations (e.g., adding a second label, removing one of three labels).
*   **Sync Layer:** The logic in `SyncController` for processing incoming messages must be robust enough to reconcile a list of server-side labels with the entries in the `MessageFolderJunction` table, handling additions and removals correctly.
*   **Entity Layer:** The `MessageEntity` itself might still contain obsolete fields related to a single folder, which should be removed.

**Plan:**
1.  **Solidify the Database Schema:**
    *   Audit `MessageEntity.kt` and remove any lingering `folderId` or similar columns. The entity must be completely unaware of which folder(s) it belongs to.
    *   Confirm that `MessageFolderJunction.kt` is correctly defined with primary keys and foreign keys with `onDelete = CASCADE`.
2.  **Refactor the DAO (`MessageDao.kt`):**
    *   Delete all methods that operate on a single `folderId` within `MessageEntity` (e.g., `updateFolderId`, `updateFolderIdForMessages`).
    *   Create new, explicit methods for managing the junction table:
        *   `addLabel(messageId: String, folderId: String)`
        *   `removeLabel(messageId: String, folderId: String)`
        *   `replaceLabels(messageId: String, newFolderIds: List<String>)` (for sync reconciliation)
3.  **Refactor the Repositories (`DefaultMessageRepository`, `DefaultThreadRepository`):**
    *   Rewrite the `moveMessage` and `moveThread` methods. A "move" should be implemented as `removeLabel(messageId, oldFolderId)` followed by `addLabel(messageId, newFolderId)`.
    *   Add new repository methods to expose the full power of the M:M model, like `applyLabelToMessage`, `removeLabelFromMessage`, etc.
4.  **Refactor the `SyncController`:**
    *   When processing incoming message data, the controller must get the list of folder/label IDs from the server for each message and use the `replaceLabels` DAO method to ensure the local state in `MessageFolderJunction` perfectly mirrors the server state.

### **ARCH-2: Complete Outstanding Technical Debt**

**Problem:** Numerous smaller pieces of unfinished work, code smells, and `TODO`s remain, which collectively reduce the stability and maintainability of the codebase.

**Plan:**
1.  **Fix Failing Tests:** The entire test suite in `GmailApiHelperTest.kt` is disabled via `// TODO: Fix test...` comments. A CI/CD pipeline is useless without a working test suite. These tests must be updated to reflect the latest API and then re-enabled.
2.  **Resolve Valid `TODO`s:** Address the remaining `TODO` comments that were left in the codebase, such as:
    *   `MicrosoftAuthManager`: Consider what should happen on a failed token persistence.
    *   `ActiveGoogleAccountHolder`: Decide if the active account needs to be persisted across app restarts.
3.  **Wire up UI Error Handling:** The `SyncController` now exposes an `error` `StateFlow`, but no UI component is observing it. A global error-display mechanism (e.g., a Snackbar in the main activity) should be implemented to show these persistent sync errors to the user.
4.  **Finalize Attachment Handling:** The logic for *sending* attachments with a draft and *parsing* attachments on received messages is incomplete. This is a core email feature that needs to be finished.
5.  **Revisit Microsoft Mappers:** The `MicrosoftAccountMappers.kt` file was difficult to edit and may contain other subtle issues. It should be reviewed and cleaned up.

---

## **4. Progress Since Plan Publication (July 4 2025)**

The remediation work described in earlier conversations has **unblocked the build** and delivered a compiling application. Key accomplishments:

1. **KSP `[MissingType]` Error Resolved**  
   • Added missing Room entity/DAO imports and cleaned circular dependencies.  
   • Provided a dedicated `PriorityBlockingQueue` via `SyncQueueModule` and removed the duplicate definition that confused Hilt/KSP.  
   • Consolidated `SyncConstants` into a single package, eliminating shadow-class references that fooled KSP's type resolver.
2. **Sync API Consistency Restored**  
   • Re-aligned `SyncJob.UploadAction` with its callers and updated all enumeration usages.  
   • Removed legacy WorkManager constants and stale repository methods.
3. **Room Schema & DAO Fixes**  
   • Added the missing helper queries to `FolderDao` and `MessageDao`.  
   • Enabled `fallbackToDestructiveMigration()` to allow development builds while the schema is still in flux.
4. **Dependency-Injection Clean-up**  
   • Eliminated circular `SyncController ↔ FolderRepository` binding.  
   • Ensured a single, singleton instance of `PriorityBlockingQueue`.
5. **Type‐Safety Pass**  
   • Converted `attachmentId` from `String` to `Long` across models, mappers, repos, and viewmodels.
6. **Build Status**  
   `./gradlew build` now finishes with **BUILD SUCCESSFUL** on all modules (`:core-db`, `:data`, `:mail`).

## 4a. Progress – July 6-7 2025 (Latest)

The following breakthroughs were delivered in the latest two increments (see commits 229c435, 7ed8660, ff0ce46):

1. **DAO Modernisation**
   • Deleted obsolete one-to-many helpers from `MessageDao`.
   • Added `addLabel`/`removeLabel` helpers to `MessageFolderJunctionDao` and updated repositories to use them.

2. **Room Schema Export**
   • Enabled `room.schemaLocation` in `core-db` build script and committed v18 JSON schema – first step toward proper migrations.

3. **Multi-Label Plumbing Complete for Gmail**
   • Added `remoteLabelIds` field to the `Message` domain model.
   • Gmail mapper now forwards full `labelIds` list.
   • `SyncController.processFetchHeaders()` reconciles server label list → junction rows, falling back gracefully when a remoteId has no local mapping yet.

4. **Microsoft Backend Compatibility**
   • Graph mapper now sets `remoteLabelIds = listOf(parentFolderId)` so the reconciliation path works uniformly even though Graph only supports single-folder.

5. **Repositories Updated**
   • `moveMessage` / `moveThread` now use fine-grained add/remove DAO calls preserving secondary labels.

6. **Build Green**
   • Full project builds successfully; KSP schema generation errors resolved.

---

## **5. Outstanding Build Warnings**

The following non-blocking issues remain visible in the Gradle output and should be triaged:

* **Unchecked cast** in `DefaultFolderRepository.kt` (generic type erasure).  
* **Parameter-name mismatch** warning in generated Room stubs (`FolderWithMessageCounts`).  
* **Room schema export** is disabled – the `schemas/` directory is empty, breaking `./gradlew verifySchema`.  
* **Destructive migrations** are still enabled – acceptable for development, but must be replaced with proper migrations before beta.

---

## **6. Immediate Next Steps (Phase 2 Continued)**

1. **Finalize the Message-Folder Many-to-Many Model**  
   • Remove obsolete `folderId` column from `MessageEntity`.  
   • Replace DAO methods that mutate `folderId` with label-aware junction operations (`addLabel`, `removeLabel`, `replaceLabels`).  
   • Update repositories (`moveMessage`, `moveThread`) to use the new DAO contract.  
   • Extend `SyncController` to reconcile server labels via `replaceLabels`.
2. **Introduce Proper Room Migrations**  
   • Generate versioned JSON schemas and add migration scripts to move away from destructive fallback.
3. **Reactivate and Repair Test Suite**  
   • Un-skip `GmailApiHelperTest.kt` and adjust assertions to the new APIs.  
   • Add unit tests for `SyncController`'s label reconciliation logic.
4. **UI Error Surface**  
   • Observe `SyncController.error` `StateFlow` in the main activity and surface as a Snackbar/toast with retry.
5. **Attachment Handling Completion**  
   • Finish multipart upload for drafts with attachments.  
   • Parse attachment metadata in incoming messages and show download UI.
6. **Microsoft Mapper Audit**  
   • Deep review of `MicrosoftAccountMappers.kt`; remove commented dead-code blocks and add unit tests.
7. **CI Hygiene**  
   • Turn schema export verification and test tasks back on in CI to prevent regression.

## 6a. Remaining Work After Latest Progress

* **Microsoft Detail Audit (ARCH-2, item 6):** Verify all Graph upload helpers (move, delete) still work with new label semantics.
* **Placeholder Folder Creation:** When Gmail returns a label not yet seen locally, SyncController currently falls back to context folder. We need a strategy to create local placeholder folders (future story).
* **Polling Optimisation:** Replace 5-second header fetch with lightweight `SyncJob.CheckForNewMail` delta call per account.
* **UI Error Surface / Attachment handling / Tests / CI** – unchanged.

---

## 7. Implementation Execution Plan (2025-07-xx)

The remaining work is grouped into four epics that **must land together** to satisfy the "monolithic" requirement.  Every bullet lists the concrete code-changes and files that will be touched.

### EPIC-A  –  Multi-Label Model Finalisation  (Placeholder folders)
1. **Create local placeholders** when a remote label/folder has no local mapping.
   * `data/sync/SyncController.kt`  – add helper `ensureLocalFolderForRemote()` and call from `processFetchHeaders()` & `handleSyncFolderList()`.
   * `core-db/entity/FolderEntity.kt` – `isPlaceholder:Boolean` column (default false).
   * `core-db/dao/FolderDao.kt` – `insertPlaceholderIfAbsent(accountId, remoteId)`.
   * `core-db/AppDatabase.kt` – schema version ➜ 20, add migration 19→20 in `core-db/migration/M19_M20.kt`.
   * **UI**: `mail/ui/folder/FolderListScreen.kt`  – hide placeholder folders (or gray them out).

### EPIC-B  –  Lightweight Delta Polling  (Check-For-New-Mail)
1. **SyncJob** `CheckForNewMail(accountId)` → priority 50.
2. `SyncController`
   * Add `handleCheckForNewMail()`; if provider reports "hasChanges"=true queue `SyncFolderList` + per-folder `FetchMessageHeaders`.
   * 5-second **active polling** now enqueues Check-jobs instead of `SyncFolderList`.
3. **MailApiService**
   * Interface method `hasChangesSince(accountId, syncToken:String?): Result<Boolean>`.
   * Implementations: `GmailApiHelper.kt`, `GraphApiHelper.kt` – call provider delta endpoint and return boolean.
4. **State**: store per-account changeToken in `AccountEntity.latestDeltaToken`.
5. **Files touched (delta)**: 8 across `core-data`, `backend-google`, `backend-microsoft`, `data`.

### EPIC-C  –  Incoming Attachment Pipeline
1. **DTOs** for attachment list & content already exist.  Hook them in:
   * `backend-google/GmailApiHelper.kt` & `backend-microsoft/GraphApiHelper.kt` – `getMessageAttachmentsInternal()` called from `processFetchHeaders()` when `hasAttachments=true`.
2. **AttachmentEntity**
   * Add `downloadStatus` enum + `localFilePath` columns already present; populate.
3. **SyncController**
   * When mapping messages, collect attachment metadata, upsert `AttachmentEntity` rows.
4. **Repositories**
   * `DefaultMessageRepository.downloadAttachment()` resurrected: enqueue `SyncJob.DownloadAttachment`.
   * Remove all legacy "AttachmentDownloadWorker" comments.
5. **DAO**: `AttachmentDao` – `insertOrUpdate` + `getDownloadedAttachmentsForMessage()` already exist.
6. **UI**: `mail/ui/message/AttachmentList.kt` – show attachment chips using new DB rows.

### EPIC-D  –  Test-Suite Resurrection & CI Hardening
1. **Unit tests**
   * Fix and re-enable `backend-google/GmailApiHelperTest.kt` (update factory wiring).
   * New tests in `data/sync/SyncControllerTest.kt` for label reconciliation & Check-For-New-Mail.
2. **Gradle / CI**
   * Ensure `./gradlew testDebugUnitTest` & `verifySchema` tasks run in GitHub Actions.
   * Add `scripts/ci-build.sh`.

### Approximate File-Touch Count
* Core-db: 7 (FolderEntity, AccountEntity, AttachmentEntity, DAOs, migration, database).
* Core-data: 3 (SyncJob, models).
* Backend-google: 3.
* Backend-microsoft: 3.
* Data-module (controller, repos, di): 12.
* Mail-module (UI): 6.
* Tests: 8.
**≈ 42 files**.

### Roll-Out Strategy
1. Create feature branch `fix2-monolith`.
2. Implement epics sequentially but **commit nothing** until the entire build+tests pass.
3. Squash all changes with commit message `feat(monolith): complete FIX2 implementation – multilabel, delta polling, attachments, tests` and fast-forward merge to `main`.

Once merged, FIX2.md can be marked **Completed ✔︎**.

> **Target Outcome:** Enter feature-freeze on the data layer within one week, paving the way for UI polish and beta release. 