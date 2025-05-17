# Melisma Mail - Backlog

### Prioritized Requirements Backlog (Epics & Status)

---
**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a
  selected folder. **(Implemented for MS & Google)**
    * *Future Enhancement:* Implement Jetpack Paging 3 for very long email lists. **(Pending)**
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to
  view its full content. **(Pending - UI/Navigation only)**
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail
  folders. **(Implemented for MS & Google)**
* **Requirement 1.4 (Unified Inbox - UI/UX):** As a user, I want an option to view emails from all
  my accounts in a single combined inbox view. **(Pending)**
* **Requirement 1.5 (Conversation View - UI/UX):** As a user, I want emails belonging to the same
  thread to be grouped. **(Done for folder views)**
    * *Note:* Folder views now group messages by conversation and display participant summaries for
      both Google and Microsoft accounts, as per the May 2025 harmonization plan.

---
**EPIC 2: Basic Mail Actions** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread. **(
  Implemented for Gmail, Pending for MS Graph API)**
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages. **(
  Implemented for Gmail, Pending for MS Graph API)**
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages. (This
  often means moving to an "Archive" folder or removing "Inbox" label for Gmail). **(Gmail:
  moveMessage can handle this, MS: Pending)**
* **Requirement 2.4 (Customizable Swipe Actions - UI/UX):** As a user, I want to configure actions
  for swipes on the message list. **(Pending)**

---
**EPIC 3: Composing & Sending** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email. **(
  Pending)**
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email. **(Pending)**
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a
  received message. **(Pending)**
* **Requirement 3.4 (Signature Management):** As a user, I want to define and manage email
  signatures. **(Pending)**

---
**EPIC 4: Attachments** (Higher Priority)

* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a
  received email. **(Pending)**
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types. *
  *(Pending)**
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email. **(
  Pending)**
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an
  email. **(Pending)**
* **Requirement 4.5 (Attach Files):** As a user, I want to easily attach files when composing an
  email. **(Pending)**

---
**EPIC 5: Account & App Foundation** (Underpins everything)

* Authentication & Core:
    * **Requirement 5.1 (Authentication - Functional):** As a user, I want to securely sign in and
      out of my supported email accounts (Microsoft Outlook: **Implemented**, Google Gmail: *
      *Implemented** using AppAuth for API authorization).
    * **Requirement 5.2 (Basic Error Handling - Functional):** As a user, I want to see clear
      messages if actions fail. (Partially Implemented - `ErrorMapperService` exists, UI toasts
      shown).
* User Experience:
    * **Requirement 5.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3
      guidelines. (Partially Implemented - Basic M3 theme in place).
    * **Requirement 5.4 (Performance - Non-Functional):** UI interactions should feel smooth and
      responsive. (Partially Implemented - Core architecture is good, but **jank observed during
      startup/initial load needs addressing - Pending**).
    * **Requirement 5.5 (Theming - UI/UX):** As a user, I want the app to support Light and Dark
      themes. **(Implemented - Dynamic Color also supported)**.
* Offline & Sync:
    * **Requirement 5.6 (Data Caching - Non-Functional):** The app should cache data locally (e.g.,
      using Room database) to improve performance and enable offline access. **(Pending - Currently
      data is fetched on demand and held in memory by repositories/ViewModel state).**
    * **Requirement 5.7 (Background Sync - Functional):** As a user, I want the app to periodically
      check for new emails. **(Pending)**
* Notifications:
    * **Requirement 5.8 (Push Notifications - Functional):** As a user, I want to receive
      notifications for new emails. **(Pending)**
    * **Requirement 5.9 (Actionable Notifications - Functional):** As a user, I want to perform
      quick actions from an email notification. **(Pending)**
* Analytics & Logging:
    * **Requirement 5.10 (Local Error Logging - Non-Functional):** The app must log critical errors
      locally. (Partially Implemented - Android Logcat used extensively. SLF4J present but not
      configured with a provider.)
    * **Requirement 5.11 (Log Export - Functional):** As a user/developer, I want a way to export
      local error logs. **(Pending)**
    * **Requirement 5.12 (Usage Metrics - Non-Functional):** The app should gather anonymized basic
      usage metrics locally. **(Pending)**
* **Requirement 5.13 (New Device Account Restoration - Google):** As a user, I want an easy way to
  restore my Google account linkage when setting up Melisma Mail on a new Android device (via
  Credential Manager's Restore Credentials feature). **(Pending - Requires testing and potential
  explicit handling)**.

---
**EPIC 6: Advanced Mail Organization** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders. **(
  Implemented for Gmail, Pending for MS Graph API)**
* **Requirement 6.2 (Advanced Search Filters):** As a user, I want to search my emails using
  advanced filters. **(Pending)**
* **Requirement 6.3 (Junk/Spam Management):** As a user, I want basic controls to mark emails as
  junk/spam. **(Pending - Though underlying APIs for moving to Spam folder exist for Gmail)**.
* **Requirement 6.4 (Folder Management):** As a user, I want to create, rename, and delete custom
  mail folders. **(Pending)**
* **Requirement 6.5 (Unsubscribe Suggestions):** As a user, I want the app to suggest an easy
  unsubscribe option. **(Pending)**
* **Requirement 6.6 (Handling Multiple Credential Types - Future):** As a user, I want the app to
  securely manage different types of credentials if other protocols beyond Google OAuth & MSAL are
  supported in the future (e.g., passkeys for other services, IMAP username/password). **(Future
  Consideration)**

---
**EPIC 7: Settings & Configuration** (Medium Priority)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (e.g., account
  management, theme). **(Partially Implemented - Account management UI via SettingsScreen)**.

---
**EPIC 8: Integrations** (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** As a user, I want to quickly create calendar events
  from email content. **(Pending)**
* **Requirement 8.2 (Task Integration):** As a user, I want to create tasks based on email content.
  **(Pending)**
* **Requirement 8.3 (Contact Integration):** As a user, I want to easily save email senders as
  device contacts. **(Pending)**

---
**EPIC 6: Settings & Configuration** (Medium Priority)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (e.g., account
  management, theme). **(Partially Implemented - Account management UI via SettingsScreen)**.

