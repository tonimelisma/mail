# Melisma Mail - System Architecture

This document provides a high-level overview of the Melisma Mail application's architecture. Its purpose is to guide developers in understanding the structure of the codebase, the responsibilities of its major components, and the flow of data through the system.

## Core Principles

1.  **Offline-First:** The UI should always be responsive and display data from the local database. All interactions that require network access are handled asynchronously.
2.  **Single Source of Truth:** The local Room database is the single source of truth for all application data. The UI observes the database for changes and updates itself accordingly.
3.  **Dependency Injection:** Hilt is used for dependency injection to manage the creation and lifecycle of components, promoting loose coupling and testability.
4.  **Layered Architecture:** The application is divided into distinct layers (UI, ViewModel, Repository, Data Source), each with a clear set of responsibilities.

## High-Level Diagram

```mermaid
graph TD
    subgraph UI Layer
        A[Compose Screens] --> B(ViewModels);
    end

    subgraph Domain Layer
        B --> C{Repositories};
        C --> D[Use Cases - Future];
    end

    subgraph Data Layer
        C --> E[SyncController];
        C --> F[DAOs (Room)];
        E --> G{Mail API Services};
        E --> F;
        F -- Reads/Writes --> H[(Database)];
        G --> I[Network (Ktor)];
    end

    I -- Syncs --> J[Email Servers];
```

## Component Breakdown

### UI Layer

*   **Compose Screen:** Provides email authoring experience. Now includes:
    *   SAF document picker for attachments (persistable URI permission).
    *   AssistChip row to preview & remove attachments.
    *   Debounced auto-save (3 s) which creates or updates drafts offline via use-cases.
    *   "Send" button emits `ACTION_SEND_MESSAGE`; message enters Outbox until SyncController uploads.
*   **Compose Screens:** Declarative UI components built with Jetpack Compose. They are responsible for displaying data and forwarding user events to ViewModels. They are stateless wherever possible.
*   **ViewModels:** Hold UI state and expose it to the Composables as `StateFlow`. They handle user events, call into the domain layer (Repositories), and are responsible for UI-specific business logic, including orchestrating permission requests before initiating authentication flows.

### Domain Layer

*   **Repositories:** The primary interface for the UI/ViewModel layer to access and manipulate data. They abstract away the origin of the data (network or local database) and provide a clean API for data operations. They are responsible for coordinating with the `SyncController` and the DAOs.

### Data Layer

*   **`SyncController`:** The brain of all data synchronization. It operates on a `PriorityBlockingQueue<SyncJob>` to process all incoming and outgoing data operations. It's responsible for:
    *   Fetching data from the remote Mail API Services.
    *   Transforming network DTOs into database entities.
    *   Persisting data to the Room database via DAOs.
    *   Handling sync errors and managing sync state.
    *   Performing efficient, delta-based polling to check for new mail.
*   **DAOs (Data Access Objects):** Room interfaces that define the SQL queries for interacting with the database tables.
*   **Room Database (`AppDatabase.kt`):** The implementation of the local SQLite database. It defines the tables (entities) and provides instances of the DAOs. The schema is built on a **many-to-many** relationship between Messages and Folders, facilitated by the `MessageFolderJunction` table. When the sync layer encounters a remote label that does not yet exist locally it **creates a placeholder `FolderEntity`** (`isPlaceholder = true`). These placeholders are hidden from the navigation drawer until a subsequent folder-list delta-sync replaces them with the full server representation.
*   **Mail API Services:** A set of interfaces (`MailApiService.kt`) and their implementations (`GmailApiHelper.kt`, `GraphApiHelper.kt`) that abstract the details of communicating with different email provider APIs (Google, Microsoft).
*   **Network (Ktor):** The HTTP client used to make network requests to the email provider APIs.

## Data Flow Example: Composing & Sending with Attachments (Offline-First)

1.  User taps "Attach" and selects a file via SAF.
2.  ComposeViewModel extracts filename/mime/size, adds `Attachment` with localUri and updates UI state.
3.  After 3 s of inactivity or on navigation away, ViewModel invokes `CreateDraftUseCase` (first save) then `SaveDraftUseCase` for edits. `DefaultMessageRepository` persists the draft message and attachment rows with `PENDING_UPLOAD` status and queues `ACTION_CREATE_DRAFT` / `ACTION_UPDATE_DRAFT`.
4.  SyncController processes pending actions immediately (foreground) or later (offline). Provider helper uploads metadata and any small attachments (<5 MB Gmail, <4 MB Graph) inline; large files stream through chunk-upload sessions (Graph) or RFC-2822 multipart (Gmail).
5.  On success, SyncController marks message & attachments `SYNCED` and removes them from Outbox list. UI re-composes.

## Data Flow Example: Refreshing the Inbox

1.  The user pulls to refresh the Inbox screen.
2.  The UI calls `viewModel.refreshInbox()`.
3.  The ViewModel submits a `SyncJob.ForceRefreshFolder` to the `SyncController`.
4.  The `SyncController` picks up the job, calls the appropriate `MailApiService` implementation (`syncMessagesForFolder` using a delta token).
5.  The `MailApiService` makes a network request to the email server.
6.  The `SyncController` receives the response, maps the DTOs to `MessageEntity` and `AttachmentEntity` objects, and saves them to the database via their respective DAOs. It also updates the `MessageFolderJunction` table to reflect the latest labels, creating placeholder `FolderEntity` records if needed.
7.  The Room database notifies its observers of the change.
8.  The ViewModel, which is observing a `Flow` from the `MessageDao`, receives the new list of messages and updates its UI state.
9.  The Compose UI automatically recomposes to display the new messages.

## Data Flow Example: Authentication Flow with Permissions

1.  **UI Trigger:** User clicks "Add Account" in `SettingsScreen`.
2.  **Permission Check (`MainViewModel`):** The `signIn` function is called. It checks if `POST_NOTIFICATIONS` permission is required (Android 13+) and if it has been granted.
3.  **Permission Request:** If permission is needed, the ViewModel emits an event. `MainActivity` observes this event and uses an `ActivityResultLauncher` to show the system permission dialog.
4.  **Auth Intent Request:** Once permission is handled, the ViewModel calls `accountRepository.signIn()`.
5.  **Flow Emission (`DefaultAccountRepository`):** The repository's `signIn` method calls the appropriate auth manager (e.g., `GoogleAuthManager`), which builds an `Intent` for the OAuth flow. The repository emits this as a `GenericAuthResult.UiActionRequired(intent)`.
6.  **Launch Auth Activity (`MainViewModel` & `MainActivity`):** The ViewModel collects the `UiActionRequired` result and forwards the `Intent` to the `MainActivity` via a `StateFlow`. The `MainActivity` launches the intent, taking the user to the Google/Microsoft sign-in screen.
7.  **Token Exchange & Persistence:** After user consent, the result is returned to the `MainActivity`'s launcher, which calls `viewModel.handleAuthenticationResult()`. This triggers the token exchange and secure persistence of the `AuthState` using the Android `AccountManager`, as detailed in the original architecture. The final `Success` or `Error` result is emitted to the ViewModel, which updates the UI.

## Data Flow Example: Lightweight Background Sync

1.  A polling timer in the `SyncController` fires (e.g., every 5 seconds in foreground, 15 minutes in background).
2.  The `SyncController` submits a `SyncJob.CheckForNewMail` for each account.
3.  The job handler calls the `MailApiService.hasChangesSince(deltaToken)` method.
4.  The API service makes a highly efficient delta query to the server.
5.  If `hasChangesSince` returns `false`, nothing else happens.
6.  If it returns `true`, the `SyncController` queues a `SyncJob.SyncFolderList` to get the full list of changes, ensuring the local state is brought back in sync with the server.
7.  The data flow then proceeds as in the "Refreshing the Inbox" example.

## Recent Maintenance

* **2025-09-15 – Build Hardening:** Removed Room **auto-migrations** from `AppDatabase` because they erroneously attempted to generate 21→23 migrations resulting in KSP failures ("new NOT NULL column 'hasFullBodyCached' added with no default value"). The project now relies solely on **explicit `Migration` objects** registered in `DatabaseModule` together with `fallbackToDestructiveMigration()` during rapid schema iteration.
* **2025-09-17 – Auth Flow Hardening:** Fixed a regression where Microsoft accounts prompted re-authentication on each launch because the active account ID was **not persisted at sign-in**. The `DefaultAccountRepository` now records the active MS account immediately alongside Google.
* **2025-09-17 – MS Graph Delta Compliance:** Replaced unsupported `/me/messages/delta` change-tracking with folder-level `/me/mailFolders/delta`. This fixes 400 *Unsupported request: Change tracking is not supported against 'microsoft.graph.message'* errors during account polling.
* **2025-06-17 – Foreground-Service Escalation:** Lowered the SyncController *work-score* threshold from 10 → 5 and added a proactive start from `SyncLifecycleObserver.onStop()`. A single opportunistic `BulkFetchBodies` job now spins up `InitialSyncForegroundService` when the app backgrounds, preventing OS background-network throttling during large backfills.
* **2025-06-17 – ConnectivityHealthTracker:** Added a singleton that tracks repeated `UnknownHostException` / time-out failures across the app and emits NORMAL/DEGRADED/BLOCKED throttle states. Producers and SyncController now consult it to pause heavy sync and prevent OS throttling cascades.
* **2025-10-20 – API & Build Clean-up:** Migrated all deprecated APIs (Room destructive-migration overload, `GlobalScope`, legacy `SyncJob`s) and fixed the remaining Kotlin compiler warnings. `GraphApiHelper` comparison logic was tightened and `MicrosoftKtorTokenProvider`'s exhaustive `when` expression simplified. Added targeted `Timber` debug logging to `SyncController`, `AndroidNetworkMonitor`, and the Microsoft Graph send-path for better observability during uploads.

## Authentication Architecture (2025-10-XX)

The project now relies on a lightweight, event-driven pattern for tracking authentication health across providers.

1. `GoogleKtorTokenProvider` and `MicrosoftKtorTokenProvider` emit `AuthEventBus.AuthSuccess(accountId, providerType)` **every time** they successfully acquire or refresh an access token.
2. `DefaultAccountRepository` collects these events and immediately clears the `needsReauthentication` flag on the corresponding `AccountEntity`.  This guarantees that the UI banner (driven by `overallApplicationAuthState`) flips back to "authenticated" as soon as a silent token refresh succeeds—without waiting for a poll or manual refresh.
3. Any component can listen to `AuthEventBus.events` for fine-grained telemetry (e.g. analytics, debug overlays).

This removes scattered calls to `setNeedsReauthentication(false)` from multiple layers and ensures a **single source of truth** for re-authentication state while keeping provider implementations decoupled from database concerns.

### Why not keep a separate global AuthState model?

An earlier iteration defined a sealed `AuthState` hierarchy in `core-data`. That model was never wired into the UI and has now been removed in favour of the simpler enum `OverallApplicationAuthState` (NONE / PARTIAL / ALL / OK).  The new bus keeps the enum accurate in real-time without an additional aggregate state machine.