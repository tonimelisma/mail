# **Melisma Mail \- Sync Architecture Refactor Plan**

Version: 1.2  
Date: June 8, 2025  
Status: Approved

## **1\. Objective**

This document outlines the plan to refactor the application's data synchronization mechanism from the current WorkManager-based SyncEngine to the centralized, priority-driven SyncController model.

## **2\. Current Architecture Overview**

The existing architecture uses a distributed SyncEngine with multiple WorkManager workers and a RemoteMediator for paging, leading to logic fragmentation and unpredictable prioritization.

## **3\. Proposed Architecture: SyncController**

The new architecture will be centered around a SyncController singleton. Key characteristics include:

* **Centralized Control** via a PriorityBlockingQueue of SyncJobs.  
* **Observable State** via a StateFlow for UI feedback.  
* **Unified Sync Logic**, consolidating all previous worker logic.  
* **Database-Driven Paging**, removing the RemoteMediator.  
* **Many-to-Many Schema** for Gmail compatibility.  
* **Stateful Background Sync** using a FolderSyncStateEntity to persist progress.

## **4\. Detailed Migration Plan**

### **Phase 1: Foundation \- SyncController and Database Schema**

1. **Define SyncJob and SyncStatus:**  
   * **Action**: Create the SyncJob sealed class and the SyncStatus data class.  
2. **Create SyncController Stub:**  
   * **Action**: Create the SyncController singleton with its priority queue, CoroutineScope, and StateFlow.  
3. **Database Migration:**  
   * **Action**: This will be a **complete "nuke and pave" destructive migration.** The existing schema and all local data will be deleted.  
   * **Action**: Implement a new Room Migration to:  
     1. Create the MessageFolderJunction table.  
     2. Create the FolderSyncStateEntity table (columns: folderId, nextPageToken).  
     3. Update the MessageEntity table (remove folderId).  
4. **Update Backup Configuration:**  
   * **Action**: Modify backup\_rules.xml to exclude the database and the attachments/ directory.
5. **Integrate User Preferences:**
   * **Action**: Inject `UserPreferencesRepository` into the `SyncController`.
   * **Action**: During initial sync, the controller will read the `initialSyncDurationDays` preference and pass a calculated date filter to the backend services to limit the scope of the sync.

### **Phase 2: Core Logic Migration \- Replacing SyncEngine**

1. **Update DI:**  
   * **Action**: Change the Hilt module to provide the SyncController singleton instead of SyncEngine.  
2. **Refactor Repositories & ViewModels:**  
   * **Action**: Replace all SyncEngine injections with SyncController. Update all calls to submit the appropriate SyncJob instead of calling engine methods.

### **Phase 3: Paging and Worker Replacement**

1. **Remove Remote Mediator:**  
   * **Action**: Delete MessageRemoteMediator.kt and RemoteKeyEntity.kt.  
2. **Simplify Paging in Repository:**  
   * **Action**: Modify the Pager factory to use a DB-only PagingSource.  
   * **Note on UX Risk & Mitigation**: The removal of on-demand paging presents a risk. This is mitigated by the **Level 1 "Predictive Scrolling" job**, which ensures the SyncController will prioritize fetching the next page for the user's active folder above all background work.  
3. **Consolidate Worker Logic into SyncController:**
   * **Action**: Port the logic from all ...Worker.kt files into private methods within SyncController and then delete the worker files.

### **Phase 4: Lifecycle and Finalization**

1. **Implement Polling Lifecycle:**  
   * **Action**: Implement the 5-second foreground polling timer that queues low-priority freshness jobs. This should be tied to the application's process lifecycle.
   * **Action**: Implement the periodic (~15 minute) `WorkManager` job for background polling.
2. **Implement Foreground Service:**  
   * **Action**: Implement the Foreground Service to be managed by the SyncController during a new account's initial sync.
3. **Final Cleanup:**  
   * **Action**: Search the codebase for any remaining references to SyncEngine, RemoteMediator, and old Worker classes, and remove them.

## **5\. Core Implementation Guarantees**

1. **Algorithm Adherence:** The implementation **must** strictly follow the defined priority algorithm.  
2. **Transaction Safety:** Each logical unit of work **must** be wrapped in a single database transaction.

> • **New Progress – 2025-06-14**  
>   • *Phase-1-C* (Data Module green build) **completed** – workers + repository compile, full project builds.  
>   • *Phase-1-D* (Retire SyncEngine) **completed** – all repositories & ViewModels now use SyncController.  
>   • *Phase-1-E2* (Schema & Pref Wiring) **completed** – junction/state DAOs added, backup rules updated, SyncController observes initialSyncDuration.  
>   • Next blocking task: **Phase-1-F – Remove MessageEntity.folderId & migrate queries**.