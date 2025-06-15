# **Melisma Mail - Backlog (Updated after Code Review)**

**Legend:** 🟢 (Completed) | 🟡 (Partial) | 🔴 (Not Started)

### **Prioritized Requirements Backlog (Epics & Status)**

**EPIC 0: ARCHITECTURAL REFACTOR & BUILD FIX** (Highest Priority - BLOCKER)

*   **Requirement 0.0 (Resolve KSP Build Blocker):** 🟢 **Completed** - The application builds successfully. Auto-migrations were disabled in favor of destructive migrations to resolve KSP `MissingType` errors during heavy development.
*   **Requirement 0.1 (Implement SyncController):** 🟢 **Completed** - The `SyncController` is fully implemented as the centralized component for all data synchronization, replacing the old `SyncEngine`.
*   **Requirement 0.2 (Database Migration to Many-to-Many):** 🟢 **Completed** - The database schema now correctly uses a `MessageFolderJunction` table for a many-to-many relationship. Placeholder folder creation is also implemented.
*   **Requirement 0.3 (Remove RemoteMediator):** 🟢 **Completed** - The Paging 3 `RemoteMediator` has been removed. Paging is now driven by the local database, which is populated by the `SyncController`'s background sync.
    *   ***Tech Debt:*** Stale comments referencing the old `RemoteMediator` architecture remain in `FolderEntity.kt` and `MainViewModel.kt`.
*   **Requirement 0.4 (Implement Sync State Observation):** 🟢 **Completed** - The `SyncController` exposes a `StateFlow<SyncControllerStatus>` which is observed by the UI to show real-time sync status, network state, and errors.
*   **Requirement 0.5 (Implement Core Sync Logic):** 🟢 **Completed** - The `SyncController` correctly implements the priority queue, uses Room transactions for data integrity, and persists sync state in `FolderSyncStateEntity`.
*   **Requirement 0.6 (Integrate Initial Sync Duration):** 🟢 **Completed** - The user preference for "initial sync duration" is integrated and used by the `SyncController`.
*   **Requirement 0.7 (Isolate Attachments for Backup Exclusion):** 🟢 **Completed** - Downloaded attachments are correctly saved to the `no_backup` directory to exclude them from Android's Auto Backup.
*   **Requirement 0.8 (Polling Lifecycle – Foreground & Background):** 🟢 **Completed** - The app correctly switches between aggressive (5s) foreground delta polling and battery-saving (15min) background polling.

**EPIC 1: Core Mail Viewing** (Highest Priority)

*   **Requirement 1.1 (View Message List):** 🟢 **Completed** - The UI displays a list of emails from the local database using Paging 3 for efficient loading.
*   **Requirement 1.2 (View Single Message):** 🟢 **Completed** - Tapping an email opens a detail view. The app correctly triggers a download for the message body if it's not cached locally.
*   **Requirement 1.3 (Folder Navigation):** 🟢 **Completed** - A navigation drawer allows users to switch between accounts and folders.
*   **Requirement 1.4 (Unified Inbox - UI/UX):** 🟢 **Completed** - A unified inbox is implemented, fetching messages from all folders with the `INBOX` well-known type.
*   **Requirement 1.5 (Conversation View - UI/UX):** 🟢 **Completed** - The app groups emails by `threadId` to provide a conversation view.

**EPIC 2: Basic Mail Actions (Offline Capable)** (High Priority)

*   **Requirement 2.1 (Mark Read/Unread):** 🟢 **Completed** - Messages and threads can be marked as read/unread. Changes are applied locally first and then queued for synchronization.
*   **Requirement 2.2 (Delete Message):** 🟢 **Completed** - Messages and threads can be deleted. The action is queued for sync.
*   **Requirement 2.3 (Archive Message):** 🟢 **Completed** - Archiving is implemented as a "move" operation.
*   **Requirement 2.4 (Customizable Swipe Actions - UI/UX):** 🔴 **Not Started** - There is no implementation for swipe actions.

**EPIC 3: Composing & Sending (Offline Capable)** (Medium-High Priority)

*   **Requirement 3.1 (Compose New Email):** 🟡 **Partial** - The backend logic to create and save drafts is implemented in the repository, but there is no UI to compose a new email. The FAB is a placeholder.
*   **Requirement 3.2 (Send Email):** 🟢 **Completed** - The backend logic for sending emails is robust. It correctly adds messages to an Outbox (conceptually, by setting a `PENDING_UPLOAD` state) and queues them for sync.
*   **Requirement 3.3 (Reply/Reply-All/Forward):** 🔴 **Not Started** - This is blocked by the lack of a compose screen. UI entry points are missing.
*   **Requirement 3.4 (Signature Management):** 🔴 **Not Started** - There is no implementation for email signatures.

**EPIC 4: Attachments (Offline Access)** (Higher Priority)

*   **Requirement 4.1 (View Attachments):** 🟢 **Completed** - The message detail screen displays a list of attachments.
*   **Requirement 4.2 (Preview Attachments):** 🟡 **Partial** - The app can download and open attachments using the Android system's file handlers, but it lacks a dedicated in-app previewer for a more integrated experience.
*   **Requirement 4.3 (Save Attachments):** 🟢 **Completed** - Attachments are downloaded to the app's private storage, making them available offline.
*   **Requirement 4.4 (Share Attachments):** 🔴 **Not Started** - There is no UI or intent handling for sharing attachments.
*   **Requirement 4.5 (Attach Files):** 🟡 **Partial** - The data models support adding attachments to a draft, but the compose UI to select and add files is missing.

**EPIC 5: Account & App Foundation (Offline-First Core)**

*   **Requirement 5.1 (Authentication):** 🟢 **Completed** - Secure sign-in/out for Google and Microsoft accounts is implemented.
*   **Requirement 5.X (Transparent Sync Status):** 🟢 **Completed** - A global status bar clearly indicates syncing, offline, and error states.
*   **Requirement 5.Y (Configurable Sync & Cache):** 🟢 **Completed** - The settings screen allows configuration of cache size and initial sync duration.
*   **Requirement 5.6 (Data Caching & Eviction):** 🟢 **Completed** - An eviction strategy is implemented in the `SyncController` to manage cache size based on usage and limits.
*   **Requirement 5.7 (Background Sync):** 🟢 **Completed** - The app uses an efficient background polling mechanism to check for and sync new mail.
*   **Requirement 5.Z (Delayed Send Notification):** 🔴 **Not Started** - There is no notification system for persistently failed send actions.

**EPIC 6: Advanced Mail Organization & Search (Offline Capable)** (Medium Priority)

*   **Requirement 6.1 (Move Message):** 🟢 **Completed** - Moving messages between folders is implemented.
*   **Requirement 6.2 (Advanced Search Filters):** 🟡 **Partial** - The backend pipeline for online search exists, but the UI for advanced filtering (beyond unread/starred) and complex queries is not implemented.
*   **Requirement 6.3 (Download/View Attachments):** 🟢 **Completed** - Functionality is in place to download and view attachments.
*   **Requirement 6.4 (Compose with Attachments):** 🟡 **Partial** - Status corrected. Backend models support this, but the UI is missing.

**EPIC 7: Settings & Configuration** (Medium Priority)

*   **Requirement 7.1 (Basic Settings):** 🟢 **Completed** - A settings screen exists for managing accounts and app preferences.
*   **Requirement 7.2 (Sync and Cache Configuration):** 🟢 **Completed** - Users can configure sync and cache settings.

**EPIC 8: Integrations** (Lower Priority)

*   **Requirement 8.1 (Calendar Integration):** 🔴 **Not Started** - The `calendar` module is a placeholder with no functional code.
*   **Requirement 8.2 (Contact Integration):** 🔴 **Not Started** - There is no implementation for contact integration.