# **Melisma Mail \- Backlog**

### **Prioritized Requirements Backlog (Epics & Status)**

**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a
  selected folder, with data sourced from local cache for offline availability.
    * **Status: Implemented** (Core functionality with offline support via OFFLINE.MD Phases 1-3)
    * *Future Enhancement:* Implement Jetpack Paging 3 for very long email lists if
      MessageRemoteMediator proves insufficient for extreme cases. **(Pending Evaluation after Phase
      4\)**
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to
  view its full content, available offline if cached.
    * **Status: Implemented** (UI/Navigation with offline body access via OFFLINE.MD Phases 1-3,
      enhanced by Phase 4 policies for body caching. Foundational work for auto-refresh on view REQ-SYNC-005 **Fully Implemented**.)
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail
  folders.
    * **Status: Implemented** (Core functionality with offline support via OFFLINE.MD Phases 1-3)
* **Requirement 1.4 (Unified Inbox \- UI/UX):** As a user, I want an option to view emails from all
  my accounts in a single combined inbox view, aggregated from local data.
    * **Status: Planned for Implementation** (Defined in OFFLINE.MD Phase 4, REQ: Unified Inbox)
* **Requirement 1.5 (Conversation View \- UI/UX):** As a user, I want emails belonging to the same
  thread to be grouped.
    * **Status: Implemented** (Done for folder views, leverages local data)

**EPIC 2: Basic Mail Actions (Offline Capable)** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread, with
  changes queued offline.
    * **Status: Implemented** (Offline queuing via OFFLINE.MD Phase 2\)
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages, with
  changes queued offline.
    * **Status: Implemented** (Offline queuing via OFFLINE.MD Phase 2\)
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages (move
  to "Archive" or remove "Inbox" label), with changes queued offline.
    * **Status: Implemented** (Handled by moveMessage with offline queuing via OFFLINE.MD Phase 2\)
* **Requirement 2.4 (Customizable Swipe Actions \- UI/UX):** As a user, I want to configure actions
  for swipes on the message list.
    * **Status: Pending**

**EPIC 3: Composing & Sending (Offline Capable)** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email, with
  drafts saved locally if offline.
    * **Status: Implemented** (Drafts saved locally, send queued offline via OFFLINE.MD Phase 2\. **Microsoft Graph attachment handling for create/update draft and send is now Fully Implemented.**)
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email, with it being
  added to an Outbox and synced when online.
    * **Status: Implemented** (Offline queuing to Outbox via OFFLINE.MD Phase 2, error handling
      REQ-ERR-003, REQ-ERR-004. **Further refined with `MessageEntity.isOutbox` flag for clearer local state management and robust attachment handling for Gmail and Microsoft Graph during send.**)
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a
  received message, with drafts/sends handled offline.
    * **Status: Implemented** (Leverages compose/send with offline queuing)
* **Requirement 3.4 (Signature Management):** As a user, I want to define and manage email
  signatures.
    * **Status: Pending**

**EPIC 4: Attachments (Offline Access)** (Higher Priority)

* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a
  received email.
    * **Status: Implemented** (Metadata available offline)
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types,
  using downloaded data if available offline.
    * **Status: Partially Implemented** (Requires robust previewer; offline access to downloaded
      attachments via OFFLINE.MD Phase 4, REQ: Full Offline Attachment Access)
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email, using
  downloaded data if available offline.
    * **Status: Implemented** (Offline access to downloaded attachments via OFFLINE.MD Phase 4, REQ:
      Full Offline Attachment Access)
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an
  email.
    * **Status: Pending**
* **Requirement 4.5 (Attach Files):** As a user, I want to easily attach files when composing an
  email.
    * **Status: Implemented** (**Gmail send functionality now robustly handles attachments by constructing `multipart/mixed` messages. Microsoft Graph attachment handling for create/update draft and send is now Fully Implemented.**)

**EPIC 5: Account & App Foundation (Offline-First Core)** (Underpins everything)

* Authentication & Core:
    * **Requirement 5.1 (Authentication \- Functional):** As a user, I want to securely sign in and
      out of my supported email accounts.
        * **Status: Implemented** (Triggers initial sync as per OFFLINE.MD Phase 4, REQ-INIT-001)
    * **Requirement 5.2 (Basic Error Handling \- Functional):** As a user, I want to see clear
      messages if actions fail, including sync issues.
        * **Status: Implemented** (ErrorMapperService exists, enhanced by OFFLINE.MD Phase 4 error
          handling UX REQ-ERR series)
* User Experience:
    * **Requirement 5.3 (Visual Polish \- Non-Functional):** The app should adhere to Material 3
      guidelines.
        * **Status: Partially Implemented** (Basic M3 theme in place)
    * **Requirement 5.4 (Performance \- Non-Functional):** UI interactions should feel smooth and
      responsive, leveraging local cache.
        * **Status: Significantly Improved** (Core architecture with offline-first reads addresses
          jank. Ongoing optimization.)
    * **Requirement 5.5 (Theming \- UI/UX):** As a user, I want the app to support Light and Dark
      themes.
        * **Status: Implemented** (Dynamic Color also supported)
* Offline & Sync:
    * **Requirement 5.6 (Data Caching \- Non-Functional):** The app should cache data locally (e.g.,
      using Room database) to improve performance and enable offline access, with clear policies.
        * **Status: Implemented** (Core of OFFLINE.MD plan. Detailed policies including configurable cache size limit REQ-CACHE-001 and the advanced, multi-tiered eviction strategy REQ-CACHE-002 are now fully implemented. Initial sync duration REQ-INIT-001 is now **Implemented**. Foundational work for selective offline download of attachments/bodies REQ-CACHE-003, including user preferences, ViewModel logic for automatic downloads, UI state display, and UI polish (retry mechanisms, animated transitions) is now **Fully Implemented** and build is stable. Auto-refresh on view REQ-SYNC-005 is now **Fully Implemented**. **`MessageEntity` now includes `isOutbox` flag, integrated into the local data lifecycle for sending messages.**)
    * **Requirement 5.7 (Background Sync \- Functional):** As a user, I want the app to periodically
      check for new emails and sync changes.
        * **Status: Implemented** (SyncEngine and WorkManager from OFFLINE.MD Phases 1 & 3, refined
          by Phase 4 sync strategies REQ-SYNC series (including REQ-SYNC-005 for individual message refresh being **Fully Implemented**), and now considers REQ-CACHE-003 download preferences for bodies/attachments during sync operations. DI for MailApiService refactored using a factory pattern. **`ActionUploadWorker` now uses direct thread-level API methods where applicable, and API helpers (`GmailApiHelper`, `GraphApiHelper`) have more complete action implementations, including robust attachment sending for Gmail and Microsoft Graph.**)
    * **Requirement 5.X (Transparent Sync Status \- Functional):** Users should have clear
      visibility into sync status and errors.
        * **Status: Implemented** (OFFLINE.MD Phase 3 UI Feedback, enhanced by Phase 4 REQ-ERR
          series, and UI now reflects download states for REQ-CACHE-003)
    * **Requirement 5.Y (Configurable Sync & Cache \- Functional):** Users should have some control
      over sync frequency (implicit via OS) and cache settings (time window, size limits).
        * **Status: Implemented** (Cache size limits REQ-CACHE-001 via `SettingsScreen` and `UserPreferencesRepository` are **Implemented**. Initial sync duration REQ-INIT-001 is now **Implemented**. Preferences for selective offline download of message bodies and attachments REQ-CACHE-003 are now **Implemented** via `SettingsScreen` and `UserPreferencesRepository`.)
* Notifications:
    * **Requirement 5.8 (Push Notifications \- Functional):** As a user, I want to receive
      notifications for new emails.
        * **Status: Pending** (Future vision in DESIGN.MD mentions Pub/Sub for Gmail/IMAP which
          would enable this)
    * **Requirement 5.9 (Actionable Notifications \- Functional):** As a user, I want to perform
      quick actions from an email notification.
        * **Status: Pending**
    * **Requirement 5.Z (Delayed Send Notification \- Functional):** Notify user if an email fails
      to send after retries.
        * **Status: Planned for Implementation** (Defined in OFFLINE.MD Phase 4, REQ-ERR-004)
* Analytics & Logging:
    * **Requirement 5.10 (Local Error Logging \- Non-Functional):** The app must log critical errors
      locally.
        * **Status: Partially Implemented** (Android Logcat used. SLF4J present but not configured
          with a provider.)
    * **Requirement 5.11 (Log Export \- Functional):** As a user/developer, I want a way to export
      local error logs.
        * **Status: Pending**
    * **Requirement 5.12 (Usage Metrics \- Non-Functional):** The app should gather anonymized basic
      usage metrics locally.
        * **Status: Pending**
* **Requirement 5.13 (New Device Account Restoration \- Google):** As a user, I want an easy way to
  restore my Google account linkage when setting up Melisma Mail on a new Android device.
    * **Status: Pending**

**EPIC 6: Advanced Mail Organization & Search (Offline Capable)** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders, with
  changes queued offline.
    * **Status: Implemented** (Offline queuing via OFFLINE.MD Phase 2\. **Action processing further refined with direct thread-level API calls in `ActionUploadWorker` and completed API methods in `GraphApiHelper`.**)
* **Requirement 6.2 (Advanced Search Filters \- Local & Online):** As a user, I want to search my
  emails using advanced filters, with local FTS search and online fallback/combination.
    * **Status: Planned for Implementation** (Defined in OFFLINE.MD Phase 4, REQ-SRCH series)
* **Requirement 6.3 (Junk/Spam Management):** As a user, I want basic controls to mark emails as
  junk/spam.
    * **Status: Pending** (Though underlying APIs for moving to Spam folder exist and can leverage
      offline queuing)
* **Requirement 6.4 (Folder Management):** As a user, I want to create, rename, and delete custom
  mail folders.
    * **Status: Pending**
* **Requirement 6.5 (Unsubscribe Suggestions):** As a user, I want the app to suggest an easy
  unsubscribe option.
    * **Status: Pending**
* **Requirement 6.6 (Handling Multiple Credential Types \- Future):** As a user, I want the app to
  securely manage different types of credentials if other protocols beyond Google OAuth & MSAL are
  supported.
    * **Status: Future Consideration**

**EPIC 7: Settings & Configuration** (Medium Priority)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (e.g., account
  management, theme).
    * **Status: Implemented** (Account management UI via `SettingsScreen`, theme selection, cache size configuration, initial sync duration configuration, message body/attachment download preference configuration (REQ-CACHE-003)).
* **Requirement 7.2 (Sync and Cache Configuration):** As a user, I want to configure initial sync
  duration and local cache size limits.
    * **Status: Implemented** (Local cache size limits **Implemented** via `SettingsScreen` (REQ-CACHE-001). Initial sync duration **Implemented** (REQ-INIT-001). Configuration for selective download of bodies/attachments (REQ-CACHE-003) **Implemented**.)

**EPIC 8: Integrations** (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** As a user, I want to quickly create calendar events
  from email content.
    * **Status: Pending**
* **Requirement 8.2 (Contact Integration):** As a user, I want to easily save email senders as
  device contacts.
    * **Status: Pending**