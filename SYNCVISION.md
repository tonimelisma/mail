### **Sync Engine v2.1: System Requirements**

This document defines the logic, state, and behavior of the Melisma Mail data synchronization engine. It supersedes previous proposals and is based on a tiered, priority-driven model.

#### **1.0 State Management & Data Models**

The following database fields are required to support the dynamic sync logic.

* **1.1 In FolderSyncStateEntity (or equivalent):**  
  * folderId (Primary Key)  
  * deltaToken / historyId: The string token from the last successful delta sync for this folder.  
* **1.2 In MessageEntity:**  
  * lastAccessedTimestamp: A nullable long representing the UTC timestamp of the last time the user opened this message. It is null by default and updated on every open action.  
  * hasFullBodyCached: A boolean flag indicating if the full, parsed body content has been successfully downloaded.  
* **1.3 In AttachmentEntity:**  
  * downloadState: An enum or integer (NOT\_DOWNLOADED, DOWNLOADED, FAILED) representing the cache state of the attachment's content.

#### **2.0 Core Sync Controller Logic**

The SyncController will use these core rules to manage its operation.

* **2.1 Dynamic Backfill Calculation:** The controller will not use an isInitialBackfillComplete flag. Instead, it will dynamically determine the need for backfill by running a query to find the oldestHeaderSyncTimestamp for a given folder and comparing it against the 90-day requirement. While SQL searches are fast, this calculation will be done judiciously to avoid excessive queries during active sync cycles.  
* **2.2 Cache-Aware Job Queuing:** A CACHE\_PRESSURE\_THRESHOLD (e.g., 90% of limit) will be used. If cache usage is at or above this threshold, no new **proactive download jobs** (Header Backfill, Bulk Body/Attachment Fetch) will be queued. This prevents the "yo-yo" effect of downloading and immediately evicting data. High-priority user-driven jobs will still be queued.  
* **2.3 Dynamic Foreground Service Management:** The decision to use a Foreground Service will be holistic and based on the total work in the queue.  
  * The controller will estimate a "work score" based on the number of messages to download, total size of attachments, etc.  
  * If this score exceeds a defined threshold, a Foreground Service will be started to guarantee completion of the batch of work.  
  * The service will stop only when the queue's work score drops below the threshold. This single mechanism covers all large jobs (initial sync, big delta sync, bulk downloads).

#### **3.0 Cache Management & Eviction**

* **3.1 Trigger:** A low-priority CACHE\_EVICTION job will run once every 24 hours or when cache usage exceeds a critical limit (e.g., 98%).  
* **3.2 Eviction Rule:** A message and all its associated data are candidates for eviction if:  
  1. The message's sentDate is older than 90 days.  
  2. The message's lastAccessedTimestamp is either null or older than **24 hours**. This grace period protects recently scrolled-to or searched items from being immediately removed.

#### **4.0 Sync Job Prioritization**

The SyncController will process jobs in a strict, tiered order. Foreground/background states do not change a job's *priority*, but they do change *which jobs get queued and how often*.

* **Priority 1: User-Driven Actions (Immediate)**  
  * **Description:** These jobs are direct results of a user's interaction and must be executed immediately for the UI to feel responsive.  
  * **Jobs Include:**  
    * SEND\_MESSAGE, CREATE\_DRAFT, UPDATE\_DRAFT  
    * FETCH\_MESSAGE\_CONTENT\_AND\_ATTACHMENTS (Triggered when user opens an email)  
    * MARK\_READ, DELETE, MOVE  
* **Priority 2: Folder & Structural Integrity (Pre-computation)**  
  * **Description:** Ensures the app's folder structure is up-to-date before trying to sync messages that might belong to new or renamed folders.  
  * **Job:** SYNC\_FOLDER\_LIST  
  * **Triggers:**  
    * Queued once when the app enters the foreground.  
    * Queued once as part of the 15-minute background sync cycle.  
* **Priority 3: Delta Sync (What's New?)**  
  * **Description:** Fetches all new or updated messages. This is the core of keeping the app current.  
  * **Job:** DELTA\_SYNC  
  * **Triggers:**  
    * **High-Frequency (Foreground):** Every 5-10 seconds for critical folders (Active, Inbox, Sent, Drafts, Junk).  
    * **Low-Frequency (Foreground):** Every 3-5 minutes for all other folders (round-robin).  
    * **Comprehensive (Background):** Every 15 minutes for **all** folders.  
* **Priority 4: Header Backfill (Completing History)**  
  * **Description:** Proactively fetches pages of older message headers to fill the 90-day offline cache.  
  * **Job:** HEADER\_BACKFILL  
  * **Trigger:** Queued by the controller's main loop whenever a folder is found to have less than 90 days of headers and the cache is not under pressure. The *frequency* of this check is higher in the foreground, leading to more frequent queuing of these jobs.  
* **Priority 5: Bulk Content Download (Opportunistic)**  
  * **Description:** Downloads message bodies and attachments in the background when no higher-priority work is pending.  
  * **Jobs Include:**  
    * BULK\_FETCH\_BODIES  
    * BULK\_FETCH\_ATTACHMENTS  
  * **Trigger:** Queued by the controller's main loop when network is available, cache is not under pressure, and all header backfill is complete.  
* **Priority 6: Maintenance (Housekeeping)**  
  * **Description:** Low-impact jobs that are not critical to real-time data.  
  * **Job:** CACHE\_EVICTION  
  * **Trigger:** Queued once every 24 hours or when the cache is full.