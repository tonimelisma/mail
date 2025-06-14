# **Melisma Mail \- Backlog (Synthesized)**

### **Prioritized Requirements Backlog (Epics & Status)**

**EPIC 0: ARCHITECTURAL REFACTOR \- SyncController Migration** (Highest Priority \- BLOCKER)

* **Requirement 0.1 (Implement SyncController):** As a developer, I want to replace the SyncEngine and distributed WorkManager architecture with the centralized SyncController model.  
  * **Status: Completed (2025-06-17)**  
* **Requirement 0.2 (Database Migration to Many-to-Many):** As a developer, I want to migrate the database schema to support a many-to-many relationship between messages and folders.  
  * **Status: Completed (2025-06-17)**  
* **Requirement 0.3 (Remove RemoteMediator):** As a developer, I want to remove the MessageRemoteMediator and rely on the SyncController's background sync to populate the database for paging.  
  * **Status: Completed (2025-06-18)**  
* **Requirement 0.4 (Implement Sync State Observation):** As a developer, I want the SyncController to expose a StateFlow\<SyncStatus\> that can be observed by the UI layer to provide real-time, global feedback on sync progress, network status, and error states.  
  * **Status: To Do**  
* **Requirement 0.5 (Implement Core Sync Logic):** As a developer, I want to ensure that the SyncController's implementation strictly follows the defined 4-level priority algorithm, uses database transactions for data integrity, and persists its background sync state in a FolderSyncStateEntity.  
  * **Status: In Progress (Upload pipeline completed 2025-06-24; cache eviction algorithm pending)**
* **Requirement 0.6 (Integrate Initial Sync Duration):** As a developer, I want to integrate the existing user preference for "initial sync duration" into the SyncController's initial sync mode.
  * **Status: To Do**
* **Requirement 0.7 (Isolate Attachments for Backup Exclusion):** As a developer, I want all downloaded attachments to be saved to a dedicated attachments/ directory which is excluded from cloud backups, as defined in the Phase 1 of the sync plan.
  * **Status: In Progress**
* **Requirement 0.8 (Polling Lifecycle – Foreground & Background):** Implement 5-second active polling and 15-minute passive WorkManager job to queue low-priority freshness jobs.  
  * **Status: Completed (2025-06-19)**

**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a selected folder, with data sourced from local cache.  
  * **Status: Implemented**  
  * *Note:* Paging is now handled by the SyncController's background sync, making RemoteMediator obsolete (see Req 0.3).  
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email to view its full content, available offline if cached, and auto-refreshed if online.  
  * **Status: Implemented**  
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail folders.  
  * **Status: Implemented**  
* **Requirement 1.4 (Unified Inbox \- UI/UX):** As a user, I want an option to view emails from all my accounts in a single combined inbox.  
  * **Status: Planned**  
* **Requirement 1.5 (Conversation View \- UI/UX):** As a user, I want emails belonging to the same thread to be grouped.  
  * **Status: Implemented**

**EPIC 2: Basic Mail Actions (Offline Capable)** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread, with changes queued for sync.  
  * **Status: Implemented**  
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages, with changes queued for sync.  
  * **Status: Implemented**  
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages, with changes queued for sync.  
  * **Status: Implemented**  
* **Requirement 2.4 (Customizable Swipe Actions \- UI/UX):** As a user, I want to configure swipe actions.  
  * **Status: Pending**

**EPIC 3: Composing & Sending (Offline Capable)** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to compose a new email, with drafts saved locally if offline.  
  * **Status: Implemented**  
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email, adding it to an Outbox to be synced.  
  * **Status: Implemented** (Robust attachment handling for both Gmail and Microsoft Graph is complete).  
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a message.  
  * **Status: Implemented**  
* **Requirement 3.4 (Signature Management):** As a user, I want to define and manage email signatures.  
  * **Status: Pending**

**EPIC 4: Attachments (Offline Access)** (Higher Priority)

* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a received email.  
  * **Status: Implemented**  
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types using downloaded data.  
  * **Status: Partially Implemented** (Offline access is implemented, but requires a more robust in-app previewer).  
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email.  
  * **Status: Implemented**  
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an email.  
  * **Status: Pending**  
* **Requirement 4.5 (Attach Files):** As a user, I want to attach files when composing an email.  
  * **Status: Implemented** (Robust sending support for attachments is complete).

**EPIC 5: Account & App Foundation (Offline-First Core)**

* **Requirement 5.1 (Authentication):** As a user, I want to securely sign in and out of my email accounts.  
  * **Status: Implemented**  
* **Requirement 5.X (Transparent Sync Status):** Users should have clear visibility into sync status and errors.  
  * **Status: Blocked by Req 0.4**  
  * **UI/UX Details:** A global status bar, contextual feedback for pull-to-refresh, and a settings diagnostic panel are planned.  
* **Requirement 5.Y (Configurable Sync & Cache):** As a user, I want to control cache settings (size limits, initial sync duration, attachment download preferences).  
  * **Status: Implemented**  
* **Requirement 5.6 (Data Caching & Eviction):** The app must cache data locally with a multi-tiered eviction strategy.  
  * **Status: Implemented**  
* **Requirement 5.7 (Background Sync):** The app must periodically check for new emails and sync changes.  
  * **Status: Blocked by EPIC 0** (Functionality is being migrated from WorkManager to the SyncController).  
* **Requirement 5.8 (Push Notifications):** As a user, I want to receive notifications for new emails.  
  * **Status: Pending**  
* **Requirement 5.Z (Delayed Send Notification):** Notify user if an email fails to send after retries.  
  * **Status: Planned**

**EPIC 6: Advanced Mail Organization & Search (Offline Capable)** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders.  
  * **Status: Implemented**  
* **Requirement 6.2 (Advanced Search Filters):** As a user, I want to search emails using advanced filters (local FTS \+ online).  
  * **Status: Planned** (Local FTS is implemented, online search depends on SyncController).  
* **Requirement 6.3 (Junk/Spam Management):** As a user, I want to mark emails as junk/spam.  
  * **Status: Pending**  
* **Requirement 6.4 (Folder Management):** As a user, I want to create, rename, and delete custom mail folders.  
  * **Status: Pending**

**EPIC 7: Settings & Configuration** (Medium Priority)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (account management, theme).  
  * **Status: Implemented**  
* **Requirement 7.2 (Sync and Cache Configuration):** As a user, I want to configure initial sync duration and local cache size limits.  
  * **Status: Implemented**

**EPIC 8: Integrations** (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** As a user, I want to create calendar events from email content.  
  * **Status: Pending**  
* **Requirement 8.2 (Contact Integration):** As a user, I want to save email senders as device contacts.  
  * **Status: Pending**