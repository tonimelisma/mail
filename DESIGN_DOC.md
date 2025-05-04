# Melisma Mail - Requirements & Architecture Draft

**Version:** 0.4 (2025-05-04) *Updated to include architectural considerations*

## 1. Overview

This document outlines the prioritized requirements and the proposed software architecture for the Melisma Mail Android application. The goal is to create a native Android email client with a user experience similar to stock Pixel apps, initially supporting Microsoft Outlook and later expanding to Google and potentially other providers. The architecture is designed to be iterative, testable, and maintainable.

## 2. Prioritized Requirements Backlog

The requirements are grouped into Epics, prioritized based on delivering core value incrementally. Features marked with `[NEW]` or `[UPDATED]` reflect the latest additions/changes.

---

**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a selected folder (e.g., Inbox) so that I can see my latest messages.
    * Display sender, subject, snippet/preview, timestamp, unread status.
    * Support pulling down to refresh the message list.
    * Handle loading states (initial load, refresh).
    * Basic pagination or infinite scrolling for loading older messages.
    * `[NEW]` Display sender avatars/pictures (lower priority).
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to view its full content so that I can read the message.
    * Display full headers (From, To, Cc, Subject, Date).
    * Render message body (handle HTML and plain text).
    * `[NEW]` Robust rendering of complex HTML emails (Rich Text Formatting - Viewer).
    * Mark message as read upon opening.
    * Handle loading state for message content.
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail folders (Inbox, Sent, Drafts, custom folders) listed previously, so I can view messages in specific folders.
    * UI mechanism to switch folders.
    * Display the selected folder's message list (using Req 1.1).
* **Requirement 1.4 (Unified Inbox - UI/UX):** `[NEW]` As a user, I want an option to view emails from all my accounts in a single combined inbox view, so I can see all new mail easily. (High Priority, may need backend support later)
* **Requirement 1.5 (Conversation View - UI/UX):** `[NEW]` As a user, I want emails belonging to the same thread to be grouped into a single conversation view, so I can follow the discussion easily. (Medium-High Priority)

**EPIC 2: Basic Mail Actions** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread from the message list or message view, so I can manage my inbox status.
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages from the message list or message view, so I can remove unwanted emails.
    * Move message to the 'Deleted Items' folder.
    * Provide undo option.
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages (if supported by the account/backend), so I can remove them from the inbox without deleting them.
    * Move message to the 'Archive' folder.
    * Provide undo option.
* **Requirement 2.4 (Customizable Swipe Actions - UI/UX):** `[NEW]` As a user, I want to configure actions (e.g., delete, archive, mark read, move) for left/right swipes on the message list, so I can quickly process emails. (Medium Priority)

**EPIC 3: Composing & Sending** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email so that I can send a message.
    * Provide fields for To, Cc, Bcc, Subject, Body.
    * `[NEW]` Basic rich text editing capabilities/toolbar for the body (bold, italics, lists, etc.).
    * `[NEW]` Easy way to attach files using a system picker (`:core-data`, `:app`). (Medium-High Priority)
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email, so the recipient(s) receive it.
    * Handle sending via the backend API.
    * Place sent message in the 'Sent Items' folder.
    * Handle errors during sending.
    * `[NEW]` Option to undo send for a short period after sending. (Medium Priority)
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a received message, so I can respond or share the email.
    * Pre-populate fields (To, Subject, quoted body) appropriately.
    * Allow editing before sending.
* **Requirement 3.4 (Signature Management):** `[NEW]` As a user, I want to define and manage email signatures, potentially assigning different signatures per account. (Medium-Low Priority)

**EPIC 4: Attachments** `[NEW EPIC - Higher Priority]`

* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a received email.
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types (Images, PDFs, simple Text/Document files) directly within the app.
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email to my device's storage.
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an email using the system share sheet.
* **Requirement 4.5 (Attach Files):** (Covered partly in 3.1) As a user, I want to easily attach files from my device when composing an email.

**EPIC 5: Account & App Foundation** (Underpins everything)

* **Authentication & Core:**
    * **Requirement 5.1 (Authentication):** (Mostly Done for Outlook) As a user, I want to securely sign in and out of my supported email accounts (starting with Outlook).
    * **Requirement 5.2 (Basic Error Handling):** As a user, I want to see clear messages if actions fail (e.g., network error, API error), so I understand what went wrong.
* **User Experience:**
    * **Requirement 5.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3 guidelines and visually resemble stock Pixel apps for a native Android feel.
    * **Requirement 5.4 (Performance - Non-Functional):** UI interactions should feel smooth and responsive. Network operations should show loading indicators.
    * **Requirement 5.5 (Theming - UI/UX):** `[NEW]` As a user, I want the app to support Light and Dark themes, ideally following the system setting. (Medium Priority)
* **Offline & Sync:**
    * **Requirement 5.6 (Data Caching - Non-Functional):** The app should cache folders, message lists, and potentially basic message content/metadata locally.
    * **Requirement 5.7 (Background Sync - Functional):** As a user, I want the app to periodically check for new emails in the background.
* **Notifications:** `[NEW]`
    * **Requirement 5.8 (Push Notifications - Functional):** As a user, I want to receive notifications for new emails. (Medium Priority - may require more than basic background sync)
    * **Requirement 5.9 (Actionable Notifications - Functional):** As a user, I want to perform quick actions (e.g., Archive, Delete, Mark Read) directly from an email notification. (Medium-High Priority)
* **Analytics & Logging:**
    * **Requirement 5.10 (Local Error Logging - Non-Functional):** The app must log critical errors locally.
    * **Requirement 5.11 (Log Export - Functional):** As a user/developer, I want a way to export local error logs.
    * **Requirement 5.12 (Usage Metrics - Non-Functional):** The app should gather anonymized basic usage metrics locally.

**EPIC 6: Advanced Mail Organization** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders so that I can organize my email.
* **Requirement 6.2 (Advanced Search Filters):** `[UPDATED]` As a user, I want to search my emails using advanced filters (keywords, sender, subject, date range, attachment presence), so I can find specific messages quickly.
* **Requirement 6.3 (Junk/Spam Management):** `[NEW]` As a user, I want basic controls to mark emails as junk/spam and manage the junk folder. (Medium Priority)
* **Requirement 6.4 (Folder Management):** `[NEW]` As a user, I want to create, rename, and delete custom mail folders. (Medium-Low Priority)
* **Requirement 6.5 (Unsubscribe Suggestions):** `[NEW]` As a user, I want the app to suggest an easy unsubscribe option for detected mailing lists. (Lower Priority)

**EPIC 7: Settings & Configuration** (Lower Priority for initial MVP)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (account view, sign out, log export).
    * (Later) Add setting for background sync frequency/notifications.
    * (Later) Add configuration for swipe actions (Req 2.4).
    * (Later) Add configuration for signatures (Req 3.4).

**EPIC 8: Integrations** `[NEW EPIC]` (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** `[NEW]` As a user, I want to quickly create calendar events from email content (e.g., detected dates, meeting invitations).
* **Requirement 8.2 (Task Integration):** `[NEW]` As a user, I want to create tasks in relevant apps (e.g., To Do, Google Tasks) based on email content.
* **Requirement 8.3 (Contact Integration):** `[NEW]` As a user, I want to easily save email senders as device contacts or view existing contact details.

**EPIC 9: Google Account Integration** (Future)

* **Requirement 9.1 (Google Authentication):** As a user, I want to securely sign in and out of my Google account.
* **Requirement 9.2 (View Google Folders/Labels):** As a user, I want to see the list of folders/labels for my signed-in Google account.
* **Requirement 9.3 (View Google Message List):** As a user, I want to see the list of emails within a selected Google folder/label.
* **Requirement 9.4 (View Single Google Message):** As a user, I want to view the full content of an email from my Google account.
* *(Mirroring other Epics for Google)* Basic Actions, Attachments, Composing/Sending, etc. for the Google account.

---

## 3. Proposed Architecture

*(The core architecture remains the same, but we acknowledge how new features integrate):*

### 3.1. Core Principles

* **UI Layer:** Jetpack Compose, ViewModels (MVVM), StateFlow.
* **Data Layer:** Repository pattern, Coroutines/Flow.
* **Dependency Injection:** Hilt (or Koin).
* **Modularity:** `:app`, `:feature-auth`, `:core-data`, `:core-database`, `:backend-microsoft`, `:backend-google` (future).

### 3.2. Proposed Modules

* As listed above.

### 3.3. Iterative Implementation Strategy & Feature Integration

* **Foundation (Epics 1, 2, 3, 5):** Build core viewing, actions, composing, and foundational elements using the MVVM/Repository pattern. Introduce DI early.
* **Attachments (Epic 4):** Requires file handling logic, permissions (if saving), potentially content providers/intents for previewing/sharing. Integrate into message view (`:app`) and repository (`:core-data`, backend modules).
* **Notifications (Req 5.8, 5.9):** Requires `NotificationManager`, potentially Foreground Services or deeper integration with `WorkManager` for push/sync. Actionable notifications need PendingIntents handled by BroadcastReceivers or Services, likely interacting with repositories via DI.
* **UI/UX Enhancements (Unified Inbox, Conversation View, Swipes, Theming):** Implemented primarily within the `:app` module, potentially requiring data layer support (e.g., querying across accounts for Unified Inbox, grouping logic for Conversation View).
* **Advanced Organization (Epic 6):** Extends repositories and potentially database queries (`:core-database`, `:core-data`, backend modules). UI changes in `:app`.
* **Integrations (Epic 8):** May require specific Android Intents to interact with Calendar/Task/Contact apps, or direct API interaction if deeper integration is needed (potentially new modules).
* **Google Backend (Epic 9):** Add `:backend-google`, update `:feature-auth`, configure DI as planned.

### 3.4. Future Architectural Considerations `[NEW SECTION]`

* **Refactoring Authentication Layer to use Kotlin Flows:**
    * **Context:** The current authentication implementation (`MicrosoftAuthManager`,
      `MicrosoftTokenProvider`) relies on bridging callback-based APIs (like MSAL) to coroutines
      using `CompletableDeferred`. Recent unit testing efforts for `MicrosoftTokenProvider`
      highlighted complexities and potential timing issues with this approach when using
      `kotlinx-coroutines-test`.
    * **Proposal for Evaluation:** As a long-term improvement, consider refactoring the relevant
      parts of the `:feature-auth` and `:backend-microsoft` (and potentially `:core-data` interfaces
      like `TokenProvider`) to use Kotlin Flows (`kotlinx.coroutines.flow`). This would involve
      changing methods like `acquireTokenSilent` and `acquireTokenInteractive` in
      `MicrosoftAuthManager` to return `Flow<AcquireTokenResult>` (likely using `callbackFlow`), and
      subsequently updating `MicrosoftTokenProvider` to consume and transform these flows instead of
      using `CompletableDeferred`.
    * **Benefits:**
        * Aligns more closely with idiomatic Kotlin coroutine patterns.
        * Significantly improves testability, allowing the use of robust Flow testing libraries like
          Turbine for clearer and more reliable verification of asynchronous behavior.
        * Enhances composability and readability of asynchronous code.
    * **Drawbacks:**
        * Requires non-trivial refactoring of existing, functional code across multiple modules.
        * Introduces a dependency on Flow knowledge and testing patterns (e.g., Turbine).
    * **Recommendation:** Keep this refactoring approach under consideration as a potential
      technical debt item or future improvement task. Evaluate its priority against new feature
      development based on ongoing maintenance and testing friction with the current
      callback/deferred approach.

---

This comprehensive backlog covers a feature-rich email client. Remember to tackle it iteratively, focusing on the highest priority epics first to deliver value progressively!
