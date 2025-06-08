# **Melisma Mail \- Sync Architecture Specification v4.1**

Version: 4.1  
Date: June 8, 2025  
Status: Approved

## **1\. Introduction & Core Principles**

This document defines the behavioral specification for the SyncController, the next-generation synchronization engine in Melisma Mail. It is designed from the ground up to be responsive, reliable, transparent, and battery-efficient, built on a four-tier priority model.

## **2\. Core Architecture: The "Dedicated Dispatcher" Model**

The heart of the new architecture is a singleton class, the **SyncController**.

* **Centralized Control:** The SyncController is the single component responsible for orchestrating all data synchronization.  
* **Job Queue:** It maintains an internal PriorityBlockingQueue of discrete SyncJob objects.  
* **Dedicated Coroutine Scope:** It operates within its own CoroutineScope, allowing fine-grained control over job execution.  
* **State Machine:** It manages its own state based on the application's lifecycle (Active, Passive, Initial Sync).

## **3\. The SyncJob: Granular Units of Work**

To enable true prioritization, all work is broken down into small, well-defined SyncJobs.  
// Represents a discrete, prioritizable unit of work  
sealed class SyncJob(val accountId: String, val priority: Int) {  
    // Level 1: Golden Rule \- User is actively waiting for this.  
    data class FetchFullMessageBody(val messageId: String) : SyncJob(priority \= 95\)  
    data class FetchNextMessageListPage(val folderId: String) : SyncJob(priority \= 90\) // For predictive scrolling  
    data class ForceRefreshFolder(val folderId: String) : SyncJob(priority \= 88\) // For user pull-to-refresh
    data class SearchOnline(val query: String) : SyncJob(priority \= 85\)

    // Level 2: Fulfilling User Intent \- Uploading user-generated changes.  
    data class UploadAction(val pendingActionId: String) : SyncJob(priority \= 75\) // e.g., send, delete, mark read

    // Level 4: Background Freshness & Backfill \- Opportunistic, battery-conscious syncing.  
    data class FetchMessageHeaders(val folderId: String, val pageToken: String?) : SyncJob(priority \= 50\)  
    data class SyncFolderList(val accountId: String) : SyncJob(priority \= 40\)  
    data class EvictFromCache(val accountId: String) : SyncJob(priority \= 10\)  
}

## **4\. The Sync Algorithm: A Strict Priority Hierarchy**

The SyncController processes jobs from its queue by strictly adhering to the following priority levels. It will **only** process jobs from a lower level if all higher-priority levels are empty.

### **Priority Level 1: The Golden Rule (Immediate User Interaction)**

This level has absolute priority and will preempt any lower-priority work.

* **1a. Content Viewing:** When a user taps to open an email, a `FetchFullMessageBody` job is created.  
* **1b. Predictive Scrolling:** When a user scrolls to the end of a message list, a `FetchNextMessageListPage` job is created to seamlessly load the next page.
* **1c. Manual Refresh:** When a user executes a pull-to-refresh gesture, a `ForceRefreshFolder` job is created. This job will reset the paging token for the folder to ensure a full refresh.
* **1d. Search:** When a user executes a search, a `SearchOnline` job is created.

### **Priority Level 2: Fulfilling User Intent**

The engine will process any pending `UploadAction` jobs (send, delete, move, etc.) to sync user changes to the server.

### **Priority Level 4: Background Freshness & Backfill (Self-Perpetuating Queue)**

This is the default, continuous work of keeping the entire local cache up-to-date when no higher-priority work exists. These jobs are queued by the internal polling mechanisms.

* **The Algorithm:** The engine syncs one page of message headers at a time. If the server response indicates more pages are available, a new `FetchMessageHeaders` job for the next page of that same folder is immediately created and added back into the low-priority queue.

## **5\. Engine States & Polling Lifecycle**

The SyncController's core processing loop is always running. The application's state (foreground or background) determines the polling strategy used to queue new sync jobs.

* **Active Polling (App in Foreground):** An aggressive polling timer runs every 5 seconds. This loop queues low-priority `FetchMessageHeaders` jobs for critical folders (e.g., Inbox). The controller's internal logic will deduplicate jobs, preventing redundant work if a job for that folder is already pending or in progress.
* **Passive Polling (App in Background):** The 5-second timer is stopped. A periodic WorkManager job is scheduled to run approximately every 15 minutes. This worker's sole responsibility is to queue a low-priority `FetchMessageHeaders` job, ensuring reasonable data freshness while conserving battery.
* **Initial Sync Mode (New Account):** A long-running sync managed by a Foreground Service. It will honor the user's "initial sync duration" preference by fetching mail only within that time window.

## **6\. Data Model & Concurrency**

* **Concurrency:** One active network operation per account at any given time.  
* **Gmail & Labels (Unified Model):** A many-to-many relationship using a MessageFolderJunction table.

## **7\. Detailed Sync Policies & Edge Case Handling**

* **Folder List Syncing:** A `SyncFolderList` job is queued once immediately after a new account is added. Subsequently, it is queued as a low-priority (Level 4) job once every 24 hours to discover new folders created on the server.
* **Cache Eviction:** An `EvictFromCache` job is a standard, low-priority (Level 4) job. It is added to the main sync queue whenever the total cache size exceeds the user-configured limit.
* **Body Snippets:** The engine **will fetch** message snippets/previews provided by the server API. These will be stored in the MessageEntity.snippet field.  
* **Background Sync State Persistence:** The nextPageToken for each folder **will be persisted** in a dedicated FolderSyncStateEntity table.  
* **Graceful Full Resync:** Detect expired sync tokens and silently trigger a full resync.  
* **Adaptive API Backoff:** Handle API throttling with per-account exponential backoff.  
* **Conflict Resolution:** The defined strategy is "last write wins". This is considered sufficient for the current implementation phase.

## **8\. Android System Integration**

### **Disabling Cache Backup**

The backup\_rules.xml file will be configured to exclude the Room database and the attachments directory from Android's Auto Backup.  
\<?xml version="1.0" encoding="utf-8"?\>  
\<full-backup-content\>  
    \<\!-- Exclude all Room database files from backup \--\>  
    \<exclude domain="database" path="." /\>  
    \<\!-- Exclude all downloaded attachments from backup \--\>  
    \<exclude domain="no\_backup" path="attachments/" /\>  
\</full-backup-content\>  
