# **Melisma Mail - Sync Architecture Remediation Plan**

Version: 2.0  
Date: July 3, 2025  
Author: Senior Architect

## **1. Executive Summary**

This document outlines the critical fixes required to stabilize the `SyncController` architecture, address severe bugs, and pay down technical debt introduced during the initial implementation. 

The high-level architecture is sound, but the implementation has critical flaws that render the application non-functional for its core purpose of sending, receiving, and managing email. The developer has left numerous features completely stubbed out and non-functional.

This plan must be executed in the order specified to ensure stability.

---

## **2. Triage: Critical Path Failures (BLOCKERS)**

These bugs represent a total failure of the application's core features. The app cannot ship until these are resolved.

### **BLOCKER 1: Fix Stubbed Domain Layer UseCases**

**Problem:** The majority of user-facing actions are silently failing because their corresponding UseCases are empty stubs that do nothing but return `Result.success(Unit)`. This is the highest priority issue.

**Affected UseCases:**
*   `DefaultSendMessageUseCase.kt` (**Bug #10**)
*   `DefaultCreateDraftUseCase.kt` (**Bug #15**)
*   `DefaultSaveDraftUseCase.kt` (**Bug #17**)
*   `DefaultDownloadAttachmentUseCase.kt` (**Bug #16**)
*   `DefaultSyncFolderUseCase.kt` (Root cause of **Bug #12**)
*   `DefaultSyncAccountUseCase.kt` (Dead code)

**Plan:**
1.  Implement the logic for every single stubbed UseCase by calling the corresponding method on the appropriate repository (e.g., `messageRepository.sendMessage(...)`).
2.  Ensure the mappers (`MessageDraftMapper.kt`) correctly transform data between the domain and data layers for these calls.
3.  For `DefaultSyncFolderUseCase`, ensure it calls a repository method that submits a `SyncJob.ForceRefreshFolder` to the `SyncController`.

### **BLOCKER 2: Fix `SyncController` `UploadAction` Job Handler**

**Problem:** Even if the UseCases were implemented, all outgoing actions would fail. The `handleUploadAction` method in `SyncController` is fundamentally broken: it ignores its `SyncJob` input and recursively calls itself with a hardcoded, non-existent action, leading to an infinite loop and guaranteed failure. This affects sending mail, marking as read, deleting, moving, etc.

**Plan:**
1.  **Rewrite `handleUploadAction`:** The method must inspect the `pendingActionDao` for the oldest pending action.
2.  It must use a `when` statement on the action's `type` (`MARK_AS_READ`, `SEND`, etc.).
3.  Inside the `when` block, it must call the correct `MailApiService` method (e.g., `service.markMessageRead(...)`).
4.  Upon successful API call, it must delete the processed action from `pendingActionDao`.
5.  If multiple actions are pending, it should re-submit another `UploadAction` job to the queue to process the next item.

---

## **3. Major Architectural & Performance Bugs**

### **BUG 3: Fix Inefficient Polling**

**Problem:** The current polling mechanism re-fetches the entire first page of the Inbox every 5 seconds, which is extremely wasteful and will quickly drain battery and data.

**Plan:**
1.  **Utilize Delta Sync:** The `MailApiService` interface already defines `syncFolders` and `syncMessagesForFolder` methods. These should be used.
2.  **Create Lightweight Check:** If a full delta-sync is too complex immediately, create a new, lightweight API endpoint and corresponding `SyncJob.CheckForNewMail` that checks for changes more efficiently than downloading the whole inbox. The `SyncController` should only trigger a full `FetchMessageHeaders` job if this check indicates new mail.

### **BUG 4: Fix Broken Pull-to-Refresh**

**Problem:** Pull-to-refresh on the message list only reloads from the local database; it does not trigger a network sync, which is the user's expectation.

**Plan:**
1.  **Fix UseCase:** The `DefaultSyncFolderUseCase` must be implemented (see BLOCKER 1).
2.  **Fix ViewModel:** `MainViewModel`'s `onRefresh` logic must be updated to call the `DefaultSyncFolderUseCase`.

### **BUG 5: Remove Zombie `MessageRemoteMediator`**

**Problem:** A `Paging 3 RemoteMediator` (`MessageRemoteMediator.kt`) still exists in the codebase and is fully wired into the database, despite the `SYNCPLAN` stating it was removed. This component writes directly to the database and will conflict with the `SyncController`.

**Plan:**
1.  **Delete Files:** Delete `data/src/main/java/net/melisma/data/paging/MessageRemoteMediator.kt`, `core-db/src/main/java/net/melisma/core_db/dao/RemoteKeyDao.kt`, and `core-db/src/main/java/net/melisma/core_db/entity/RemoteKeyEntity.kt`.
2.  **Update Database:** In `AppDatabase.kt`, remove `RemoteKeyEntity::class` from the `entities` array and remove the `remoteKeyDao()` abstract function.

### **BUG 6: Fix Obsolete Logic in `MainViewModel`**

**Problem:** `MainViewModel` contains obsolete logic. It directly calls repository methods like `setTargetFolder` which are now no-ops.

**Plan:**
1.  Remove all calls to `setTargetFolder` from the `init` block and `onFolderSelected` method in `MainViewModel.kt`.
2.  The ViewModel should only call UseCases to trigger actions or Repositories to observe data flows.

### **BUG 7: Fix Inconsistent ID Strategy in Mappers**

**Problem:** The mappers are inconsistent. Some use the local DB ID as the canonical ID in the domain model, while others use the remote server ID. This will lead to data corruption. The local, autogenerated `folder.id` should be the primary key throughout the app, with `remoteId` used only for network calls.

**Plan:**
1.  **Audit All Mappers:** Review every `toDomainModel` and `toEntity` function.
2.  **Enforce Consistency:** Ensure that the `id` field of all domain models (`MailFolder`, `Message`, etc.) is always populated from the `id` field of the corresponding database `Entity`.

---

## **4. General Technical Debt & Code Hygiene**

### **TD 1: Refactor `MailApiService` Access**

**Problem:** The codebase contains two patterns for getting a `MailApiService`: an older `MailApiServiceSelector` and a newer `MailApiServiceFactory`. The critical `SyncController` still uses the old, inferior `Selector`.

**Plan:**
1.  Refactor `SyncController` to inject and use the `MailApiServiceFactory`.
2.  Delete `DefaultMailApiServiceSelector.kt` and `MailApiServiceSelector.kt`.
3.  Remove the selector binding from DI modules.

### **TD 2: Remove Obsolete Repository Logic**

**Problem:** Repositories like `DefaultThreadRepository` and `DefaultFolderRepository` contain old, complex, and now-obsolete logic for triggering network fetches and managing state.

**Plan:**
1.  Rip out all network-related logic (`ensureNetworkFetch`, etc.) from the repositories.
2.  Repositories should have two main responsibilities: exposing `Flows` of data from DAOs, and providing methods to be called by UseCases that queue actions with the `SyncController`.

### **TD 3: Implement Comprehensive Error Handling in `SyncController`**

**Problem:** The `SyncController`'s `handle...` methods have empty `catch` blocks that just log exceptions. There is no retry logic, backoff strategy, or mechanism to surface persistent errors to the user.

**Plan:**
1.  Implement a robust retry-with-backoff policy for failed jobs.
2.  For permanent failures, update the `SyncControllerStatus` with a meaningful error message to be displayed in the UI.

### **TD 4: Add Missing Database Indices**

**Problem:** The DAOs define many queries that filter and sort, but the underlying `Entity` classes are missing `@Index` annotations. This will cause poor performance on large mailboxes.

**Plan:**
1.  Analyze queries in `MessageDao.kt` and `FolderDao.kt`.
2.  Add `@Index` annotations to `MessageEntity.kt` and `FolderEntity.kt` for frequently queried columns like `timestamp`, `folderId`, `accountId`, and `threadId`.

### **TD 5: Miscellaneous Cleanup**

*   Rename `ActionUploadWorker.kt` to something more appropriate like `SyncConstants.kt`, as it is not a worker.
*   Resolve all `// TODO` comments left in the database DAOs and mappers.
*   Fix the race condition in `DefaultFolderRepository` where `latestDbStates` and `currentApiStates` can conflict. The DAO should be the single source of truth. 