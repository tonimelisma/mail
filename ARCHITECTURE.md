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

*   **Compose Screens:** Declarative UI components built with Jetpack Compose. They are responsible for displaying data and forwarding user events to ViewModels. They are stateless wherever possible.
*   **ViewModels:** Hold UI state and expose it to the Composables as `StateFlow`. They handle user events, call into the domain layer (Repositories), and are responsible for UI-specific business logic.

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

## Data Flow Example: Lightweight Background Sync

1.  A polling timer in the `SyncController` fires (e.g., every 5 seconds in foreground, 15 minutes in background).
2.  The `SyncController` submits a `SyncJob.CheckForNewMail` for each account.
3.  The job handler calls the `MailApiService.hasChangesSince(deltaToken)` method.
4.  The API service makes a highly efficient delta query to the server.
5.  If `hasChangesSince` returns `false`, nothing else happens.
6.  If it returns `true`, the `SyncController` queues a `SyncJob.SyncFolderList` to get the full list of changes, ensuring the local state is brought back in sync with the server.
7.  The data flow then proceeds as in the "Refreshing the Inbox" example.