# Melisma Mail - Requirements & Architecture Draft

**Version:** 0.1 (2025-04-28)

## 1. Overview

This document outlines the prioritized requirements and the proposed software architecture for the
Melisma Mail Android application. The goal is to create a native Android email client with a user
experience similar to stock Pixel apps, initially supporting Microsoft Outlook and later expanding
to Google and potentially other providers. The architecture is designed to be iterative, testable,
and maintainable.

## 2. Prioritized Requirements Backlog

The requirements are grouped into Epics, prioritized based on delivering core value incrementally.

---

**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a
  selected folder (e.g., Inbox) so that I can see my latest messages.
    * Display sender, subject, snippet/preview, timestamp, unread status.
    * Support pulling down to refresh the message list.
    * Handle loading states (initial load, refresh).
    * Basic pagination or infinite scrolling for loading older messages.
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to
  view its full content so that I can read the message.
    * Display full headers (From, To, Cc, Subject, Date).
    * Render message body (handle HTML and plain text).
    * Mark message as read upon opening.
    * Handle loading state for message content.
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail
  folders (Inbox, Sent, Drafts, custom folders) listed previously, so I can view messages in
  specific folders.
    * UI mechanism to switch folders.
    * Display the selected folder's message list (using Req 1.1).

**EPIC 2: Basic Mail Actions** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread from
  the message list or message view, so I can manage my inbox status.
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages from the
  message list or message view, so I can remove unwanted emails.
    * Move message to the 'Deleted Items' folder.
    * Provide undo option.
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages (if
  supported by the account/backend), so I can remove them from the inbox without deleting them.
    * Move message to the 'Archive' folder.
    * Provide undo option.

**EPIC 3: Composing & Sending** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email so
  that I can send a message.
    * Provide fields for To, Cc, Bcc, Subject, Body.
    * Basic text editing capabilities for the body.
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email, so the recipient(
  s) receive it.
    * Handle sending via the backend API.
    * Place sent message in the 'Sent Items' folder.
    * Handle errors during sending.
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a
  received message, so I can respond or share the email.
    * Pre-populate fields (To, Subject, quoted body) appropriately.
    * Allow editing before sending.

**EPIC 4: Account & App Foundation** (Underpins everything)

* **Requirement 4.1 (Authentication):** (Mostly Done for Outlook) As a user, I want to securely sign
  in and out of my supported email accounts (starting with Outlook).
    * Handle token refresh silently in the background.
    * Handle token expiration / re-authentication prompts gracefully.
* **Requirement 4.2 (Basic Error Handling):** As a user, I want to see clear messages if actions
  fail (e.g., network error, API error), so I understand what went wrong.
* **Requirement 4.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3
  guidelines and visually resemble stock Pixel apps for a native Android feel.
* **Requirement 4.4 (Performance - Non-Functional):** UI interactions (scrolling lists, opening
  messages) should feel smooth and responsive. Network operations should show loading indicators and
  not block the UI thread.
* **Requirement 4.5 (Data Caching - Non-Functional):** The app should cache folders and message
  lists locally to provide faster startup/navigation and basic offline viewing capabilities.
* **Requirement 4.6 (Background Sync - Functional):** As a user, I want the app to periodically
  check for new emails in the background (for the selected account/folders) so that my message list
  stays reasonably up-to-date.
    * Provide visual indication of new/unread messages.
    * Sync frequency should be configurable (later, in Settings Epic).

**EPIC 5: Advanced Mail Organization** (Medium Priority)

* **Requirement 5.1 (Move Message):** As a user, I want to move messages between folders so that I
  can organize my email.
* **Requirement 5.3 (Search):** As a user, I want to search my emails based on keywords, sender, or
  subject, so I can find specific messages quickly (initially within the current account).

**EPIC 6: Settings & Configuration** (Lower Priority for initial MVP)

* **Requirement 6.1 (Basic Settings):** As a user, I want access to basic settings, such as viewing
  the logged-in account and signing out.
    * (Later) Add setting for background sync frequency.

**EPIC 7: Google Account Integration** (Future)

* **Requirement 7.1 (Google Authentication):** As a user, I want to securely sign in and out of my
  Google account.
* **Requirement 7.2 (View Google Folders/Labels):** As a user, I want to see the list of
  folders/labels for my signed-in Google account.
* **Requirement 7.3 (View Google Message List):** As a user, I want to see the list of emails within
  a selected Google folder/label.
* **Requirement 7.4 (View Single Google Message):** As a user, I want to view the full content of an
  email from my Google account.
* *(Mirroring other Epics for Google)* Basic Actions, Composing/Sending, etc. for the Google
  account.

---

## 3. Proposed Architecture

### 3.1. Core Principles

* **UI Layer:** Jetpack Compose, ViewModels (MVVM), unidirectional data flow (StateFlow).
* **Data Layer:** Repository pattern abstracting data sources (remote, local cache). Kotlin
  Coroutines/Flow for async operations.
* **Dependency Injection:** Hilt (or Koin) for managing dependencies, enabling testability and
  flexibility.
* **Modularity:** Separation into distinct Gradle modules for concerns like data contracts, backend
  implementations, database, authentication, and the main application UI.

### 3.2. Proposed Modules

* **`:app`**: Main application module (UI Screens, ViewModels, Navigation, DI Root).
* **`:feature-auth`**: Authentication logic (MSAL, Google Sign-In).
* **`:core-data`**: Shared interfaces (`MailRepository`, etc.) and backend-agnostic data models.
* **`:core-database`**: Room database implementation (Entities, DAOs, `LocalDataSource`).
* **`:backend-microsoft`**: Microsoft Graph API implementation (`RemoteDataSource`,
  `RepositoryImpl`, Networking).
* **`:backend-google`**: (Future) Gmail API implementation (similar structure).

### 3.3. Iterative Implementation Strategy

The architecture supports building features epic by epic:

1. **Epic 1 (Core Mail Viewing) & Initial Caching (Req 4.5):** Implement basic UI screens/ViewModels
   in `:app`. Define Repositories/Models in `:core-data`. Implement Room caching in
   `:core-database`. Implement MS Graph API calls and initial Repository logic in
   `:backend-microsoft`. Wire up with DI.
2. **Epic 2 (Basic Mail Actions):** Extend Repository interfaces (`:core-data`), add remote API
   calls (`:backend-microsoft`), update local cache (`:core-database`), and add UI/ViewModel logic (
   `:app`).
3. **Epic 3 (Composing & Sending):** Add Compose UI/ViewModel (`:app`), extend Repository (
   `:core-data`), implement sending logic (`:backend-microsoft`).
4. **Epic 4 (Foundation - Background Sync - Req 4.6):** Implement `WorkManager` for background sync,
   likely using Repository methods. Enhance caching and error handling. Refine Material 3 theme in
   `:app`.
5. **Epic 5 (Advanced Org):** Extend repositories/data sources for move/search (`:core-data`,
   `:backend-microsoft`, `:core-database`). Implement UI in `:app`.
6. **Epic 6 (Settings):** Add simple Settings screen/ViewModel in `:app`. Use Preferences
   DataStore (via `:core-data` interface if needed) for simple settings.
7. **Epic 7 (Google Backend):** Add Google auth to `:feature-auth`. Create `:backend-google` module
   implementing `:core-data` interfaces using Gmail API. Update DI to provide the correct
   implementation based on account type.

This approach ensures a working application slice after each epic, built upon a testable and
extensible foundation.
