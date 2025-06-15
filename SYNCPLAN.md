# **Melisma Mail - Sync Architecture Refactor Plan**

> **Overall Status:** <span style="color:green">**ALL PHASES COMPLETE - ✅**</span> (As of 2025-07-02)

**Legend:**
*   <span style="color:green">**[DONE - ✅]**</span>
*   <span style="color:orange">**[WIP - 🚧]**</span>
*   <span style="color:red">**[TODO - ❌]**</span>

---
Version: 1.3 (Reflects completed status)
Date: July 3, 2025  
Status: Implemented

## **1\. Objective**

This document outlines the plan to refactor the application's data synchronization mechanism from the current WorkManager-based SyncEngine to the centralized, priority-driven SyncController model.

## **2\. Current Architecture Overview**

The existing architecture uses a distributed SyncEngine with multiple WorkManager workers and a RemoteMediator for paging, leading to logic fragmentation and unpredictable prioritization.

## **3\. Proposed Architecture: SyncController**

The new architecture will be centered around a SyncController singleton. Key characteristics include:

*   **Centralized Control** via a PriorityBlockingQueue of SyncJobs.  
*   **Observable State** via a StateFlow for UI feedback.  
*   **Unified Sync Logic**, consolidating all previous worker logic.  
*   **Database-Driven Paging**, removing the RemoteMediator.  
*   **Many-to-Many Schema** for Gmail compatibility.  
*   **Stateful Background Sync** using a FolderSyncStateEntity to persist progress.

## **4\. Detailed Migration Plan**

### **Phase 1: Foundation \- SyncController and Database Schema**

1.  **[DONE - ✅]** **Define SyncJob and SyncStatus:**  
    *   **Action**: Create the SyncJob sealed class and the SyncStatus data class.
2.  **[DONE - ✅]** **Create SyncController Stub:**  
    *   **Action**: Create the SyncController singleton with its priority queue, CoroutineScope, and StateFlow.
3.  **[DONE - ✅]** **Database Migration:**  
    *   **Action**: This will be a **complete "nuke and pave" destructive migration.** The existing schema and all local data will be deleted.  
    *   **Action**: Implement a new Room Migration to:  
        1.  Create the MessageFolderJunction table.  
        2.  Create the FolderSyncStateEntity table (columns: folderId, nextPageToken).  
        3.  Update the MessageEntity table (remove folderId).  
4.  **[DONE - ✅]** **Update Backup Configuration:**  
    *   **Action**: Modify backup\_rules.xml to exclude the database and the attachments/ directory inside no_backup.
    *   **Status:** Completed on 2025-06-26 – backup_rules.xml now excludes `no_backup/attachments/` and SyncController writes downloaded attachments there.
5.  **[DONE - ✅]** **Integrate User Preferences:**
    *   **Action**: Inject `UserPreferencesRepository` into the `SyncController`.
    *   **Action**: During initial sync, the controller will read the `initialSyncDurationDays` preference and pass a calculated date filter to the backend services to limit the scope of the sync.
    *   **Status:** Completed on 2025-06-30 – SyncController now applies the earliest‐timestamp filter for the first page of every folder sync.

### **Phase 2: Core Logic Migration \- Replacing SyncEngine**

1.  **[DONE - ✅]** **Update DI:**  
    *   **Action**: Change the Hilt module to provide the SyncController singleton instead of SyncEngine.
2.  **[DONE - ✅]** **Refactor Repositories & ViewModels:**  
    *   **Action**: Replace all SyncEngine injections with SyncController. Update all calls to submit the appropriate SyncJob instead of calling engine methods.

### **Phase 3: Paging and Worker Replacement**

1.  **[DONE - ✅]** **Remove Remote Mediator:**  
    *   **Action**: Delete MessageRemoteMediator.kt and RemoteKeyEntity.kt.
    *   **Status**: Completed on 2025-06-18 (see SYNCLOG).
2.  **[DONE - ✅]** **Simplify Paging in Repository:**  
    *   **Action**: Modify the Pager factory to use a DB-only PagingSource.  
    *   **Note on UX Risk & Mitigation**: The removal of on-demand paging presents a risk. This is mitigated by the **Level 1 "Predictive Scrolling" job**, which ensures the SyncController will prioritize fetching the next page for the user's active folder above all background work.  
    *   **(Update 2025-07-01: Online search pipeline implemented – `SearchOnline` no longer a stub.)**
3.  **[DONE - ✅]** **Consolidate Worker Logic into SyncController:**
    *   **Action**: Port the logic from all ...Worker.kt files into private methods within SyncController and then delete the worker files.
    *   **Status**: Completed between 2025-06-21 and 2025-06-23 (see SYNCLOG).

### **Phase 4: Lifecycle and Finalization**

1.  **[DONE - ✅]** **Implement Polling Lifecycle:**  
    *   **Action:** Implement the 5-second foreground polling timer that queues low-priority freshness jobs.
    *   **Action:** Implement the periodic (~15 minute) background polling mechanism.
    *   **Status:** Completed on 2025-06-19 and 2025-06-23. The `PassivePollingWorker` was fully absorbed into the `SyncController`'s internal coroutine loop, removing the `WorkManager` dependency entirely.
2.  **[DONE - ✅]** **Implement Foreground Service:**  
    *   **Action**: Implement the Foreground Service to be managed by the SyncController during a new account's initial sync.
    *   **Status**: Completed on 2025-07-02 with `InitialSyncForegroundService`.
3.  **[DONE - ✅]** **Final Cleanup:**  
    *   **Action**: Search the codebase for any remaining references to SyncEngine, RemoteMediator, and old Worker classes, and remove them.
    *   **Status**: Completed as part of worker consolidation and SyncEngine retirement (see SYNCLOG 2025-06-21).
4.  **[DONE - ✅]** **New Progress (Cache eviction & mutex):**
    *   **Action**: Implement the Cache eviction job wiring and per-account mutex.
    *   **Status**: Completed. Mutex on 2025-06-20, Cache Eviction on 2025-06-25 (see SYNCLOG).
5.  **[DONE - ✅]** **Message/Attachment Download Internalised & Workers Deleted:**
    *   **Action**: See SYNCLOG 2025-06-22.
6.  **[DONE - ✅]** **Phase-4 B** (Worker consolidation) **completed** – internalised folder/message/attachment handlers, WorkManager stripped (SYNCLOG 2025-06-23).
7.  **[DONE - ✅]** **Phase-4 C** (Per-account mutex & polling) **completed** – concurrency guard, active/passive polling in-place (see SYNCLOG 2025-06-19 & 23).
8.  **[DONE - ✅]** **Phase-4 D** (PendingAction upload pipeline) **completed** – SyncController now processes PendingAction queue (SYNCLOG 2025-06-24).
9.  **[DONE - ✅]** **Phase-4 E** (Cache eviction algorithm) **completed** – full `runCacheEviction()` implementation integrated into SyncController and passive polling queues EvictFromCache jobs (SYNCLOG 2025-06-25).
10. **[DONE - ✅]** **Phase-4 F** (Sync State Observation) **completed** – `SyncController.status` exposed to UI, status bar implemented (SYNCLOG 2025-06-27).

## **5\. Core Implementation Guarantees**

1.  **[DONE - ✅]** **Algorithm Adherence:** The implementation **must** strictly follow the defined priority algorithm.
2.  **[DONE - ✅]** **Transaction Safety:** Each logical unit of work **must** be wrapped in a single database transaction.

> • **New Progress – 2025-06-14** (Retained for historical context)
> • *Phase-1-C* (Data Module green build) **completed** – workers + repository compile, full project builds.  
> • *Phase-1-D* (Retire SyncEngine) **completed** – all repositories & ViewModels now use SyncController.  
> • *Phase-1-E2* (Schema & Pref Wiring) **completed** – junction/state DAOs added, backup rules updated, SyncController observes initialSyncDuration.  
> • *Phase-1-F* (FolderId removal) **completed** – see SYNCLOG 2025-06-17.  
> • *Phase-3-A* (Remove RemoteMediator & DB-only paging) **completed** – MessageRemoteMediator removed from repository, self-perpetuating SyncController pagination implemented. Build green.
> • *Phase-A (Toolchain Upgrade)* **completed** – Kotlin 2.1.21, KSP 2.1.21-2.0.2, Compose BOM 2025.06.00 integrated (SYNCLOG 2025-06-20).