# **Melisma Mail \- Architectural Design & Developer Guide**

Version: 2.3 (Offline-First Enhancements)  
Date: June 1, 2025

## **1\. Introduction & Project Vision**

### **1.1. Overview**

Melisma Mail is an Android email client designed to offer a clean, intuitive, and native user
experience, adhering to Material 3 design principles. It aims to be a compelling alternative to
default email applications, initially supporting Microsoft Outlook and Google (Gmail) accounts, with
a focus on modern Android development practices and a robust offline-first architecture.

### **1.2. Core Goals**

* **User Experience:** Provide a seamless, performant, and visually appealing interface that works
  smoothly online and offline. Ensure users have access to a significant portion of their recent
  mail data even without connectivity.
* **Modern Android Practices:** Leverage Kotlin, Jetpack Compose, Coroutines, Flow, Hilt, Room, and
  WorkManager.
* **Security:** Ensure secure handling of user credentials and data, particularly OAuth tokens.
* **Modularity:** Maintain a well-defined, modular codebase for better maintainability, scalability,
  and testability.
* **Data Transparency & Control:** Provide clear feedback on synchronization status, errors, and
  offer users reasonable control over data caching and offline behavior.

### **1.3. Key Technologies**

* **UI:** Jetpack Compose
* **Architecture:** Offline-First MVVM with a Domain Layer (Use Cases)
* **Local Storage:** Room (with FTS for local search, and lastAccessedTimestamp for intelligent
  caching)
* **Background Processing:** WorkManager
* **Asynchronous Programming:** Kotlin Coroutines & Flow
* **Dependency Injection:** Hilt
* **Networking:** Ktor (with OkHttp engine)
* **Authentication:**
    * Google: AppAuth for Android (orchestrated by GoogleAuthManager) for OAuth 2.0 authorization
      code flow and token management.
    * Microsoft: MSAL (Microsoft Authentication Library) for Android.
* **Security:** Android Keystore via a custom SecureEncryptionService.

## **2\. Core Architectural Principles**

### **2.1. Overall Architecture**

Melisma Mail employs a layered architecture designed for an offline-first experience:

* **UI Layer (:app module):** Responsible for presenting data to the user. Interacts exclusively
  with the local database via ViewModels and Use Cases. Handles user interactions like
  pull-to-refresh.
* **Domain Layer (:domain module):** Contains discrete business logic in the form of Use Cases.
* **Data Layer (:data module):** Implements repository interfaces. Its primary role is to act as a *
  *synchronizer** between network data sources (APIs) and the local database, managing data
  fetching, caching (initial sync, delta sync), action queuing, and local search. Orchestrated by a
  SyncEngine.
* **Database Layer (:core-db module):** Defines the Room database schema, entities (with sync
  metadata like SyncStatus, lastSuccessfulSyncTimestamp, lastAccessedTimestamp, and FTS
  capabilities), and Data Access Objects (DAOs).
* **Backend/Provider Layer (:backend-google, :backend-microsoft modules):** Handles all
  provider-specific API communication, including support for delta queries.
* **Contracts Layer (:core-data module):** Defines interfaces and data models for the application.

### **2.2. Offline-First and Single Source of Truth**

The application is architected to be "offline-first". The UI layer is completely decoupled from the
network. It observes Flows directly from the Room database DAOs. All user mutations (deleting,
marking as read, sending) are applied to the local database first (optimistic updates), then queued
for synchronization with the server. The local database, defined in :core-db, is the **single source
of truth** for the entire application state. Data management policies (initial sync, cache size
limits, eviction rules based on age and recent access) ensure a balance between offline availability
and device resource usage.

### **2.3. MVVM (Model-View-ViewModel)**

The UI layer utilizes the MVVM pattern.

* **Views (Composables):** Observe state changes from ViewModels and render the UI. They delegate
  user actions to ViewModels and present sync status/error information.
* **ViewModels:** Prepare and manage UI-related data (state) for the Views. They delegate all
  business logic and data operations to Use Cases and manage UI feedback for sync operations.
* **Models:** Represent the data structures (e.g., Account, Message, MailFolder).

### **2.4. Domain Layer & Use Cases**

All business logic is encapsulated within single-purpose UseCase classes in the :domain module. This
ensures ViewModels remain lightweight. Key use cases include SignInUseCase, ObserveMessagesUseCase,
MarkAsReadUseCase, SyncAccountUseCase, SearchMessagesUseCase, GetSyncStatusUseCase.

### **2.5. Repository Pattern**

Repositories in the :data module abstract data sources and manage the synchronization logic between
the network and the local database, including delta sync for changes and deletions, and triggering
WorkManager tasks via the SyncEngine.

### **2.6. Modularity**

The application is divided into functionally distinct modules to promote separation of concerns,
reusability, and faster build times.

### **2.7. Dependency Injection (Hilt)**

Hilt is used for managing dependencies throughout the application.

### **2.8. Asynchronous Operations & Sync Strategy**

The application uses a multi-faceted strategy for data synchronization, managed primarily by a
SyncEngine orchestrating WorkManager tasks.

1. **Initial Account Sync:**
    * A one-time WorkManager job (InitialAccountSyncWorker) triggered on new account login.
    * Downloads message headers, bodies, and attachments for the last 90 days (configurable), capped
      at 0.5GB total. Individual attachments over 25MB are skipped for this bulk download (metadata
      only).
2. **Periodic Background Sync:**
    * A recurring PeriodicWorkRequest via WorkManager (e.g., FolderContentSyncWorker) to check for
      new mail and other updates at user-defined intervals (e.g., every 15 minutes), ensuring
      battery efficiency. This performs delta synchronization for all relevant folders.
3. **Foreground Sync & Activity-Driven Sync:**
    * **Constant Polling (Key Folders):** When the app is in the foreground, the SyncEngine ensures
      frequent, short-interval polling (delta sync) for critical folders: Inbox, Drafts, and Outbox.
    * **Folder Open Sync (Other Folders):** When a user opens any other folder, if the app is online
      and that folder's local data (based on lastSuccessfulSyncTimestamp) is older than 5 minutes (
      configurable) and hasn't been recently checked by general foreground polling, the SyncEngine
      initiates a sync for that specific folder.
    * **Individual Message Refresh:** When an individual email is opened for viewing, if online, the
      app automatically attempts to silently refresh that message's content (via SyncEngine) if it's
      potentially stale.
    * **Pull-to-Refresh:** User-initiated pull-to-refresh on message lists triggers an immediate,
      high-priority sync request to the SyncEngine for the current folder.
4. **Delta Synchronization:** All sync operations (after initial) aim to fetch only changes (new
   messages, updates, deletions) since the last successful sync, using mechanisms like historyId (
   Gmail) or deltaToken (Graph). This includes robust handling of server-side deletions (
   REQ-SYNC-006) to keep the local cache consistent.
5. **Action Sync (ActionUploadWorker):** User-initiated actions are queued and processed by a
   dedicated WorkManager worker. It attempts to sync the action with the server, with automatic
   retries (5 attempts, exponential backoff: 1m, 5m, 15m, 30m, 1hr). Failed actions are clearly
   indicated in the UI (e.g., Outbox) with manual retry options.
6. **Future Vision (Pub/Sub):** Evolve to a direct-to-client connection model where supported (
   Google Cloud Pub/Sub for Gmail, IMAP IDLE). For providers like Microsoft Graph that do not
   support a direct client-side stream, the smart sync polling mechanism will remain.

### **2.9. Data Caching and Eviction**

* **Overall Cache Size Limit:** 0.5 GB default (configurable: 0.5GB, 1GB, 2GB, 5GB).
* **Eviction Policy (CacheCleanupWorker):**
    * **Exclusions:** Items pending sync actions; messages (bodies, attachments) accessed within the
      last 90 days.
    * **Priority:** If cache exceeds limit, evict to 80% of limit:
        1. Data (attachments, then bodies, then headers) for messages older than 90 days, oldest
           first.
        2. If needed, data for messages within 90 days but not recently accessed, oldest/least
           recently accessed first.
* lastAccessedTimestamp field in MessageEntity tracks user interaction for eviction decisions.

### **2.10. Error Handling and UX**

* **Transparency:** Clear UI indicators for overall sync status, item-specific sync status (pending,
  error), and pending actions (e.g., in Outbox).
* **Automatic Retries:** For transient network/server issues (5 attempts, exponential backoff).
* **User Control:** Manual retry option for persistently failed actions.
* **Notifications:**
    * Delayed send notification (non-modal system notification if email unsent \>1hr, background,
      network available).
    * Prolonged general sync failure notification (non-modal banner if no sync \>24hrs despite
      network and attempts; suppressed if intentionally offline).
* SyncStatus enum, lastSyncError, lastSyncAttemptTimestamp fields in entities provide data for error
  reporting.

## **3\. Module Architecture Deep Dive**

### **3.1. :app Module**

* **Purpose & Scope:** UI, navigation, user interaction handling, display of sync status.
* **Key Components & Classes:** MainActivity.kt, ViewModels, UI Composables (including settings for
  sync/cache).

### **3.2. :core-data Module**

* **Purpose & Scope:** Defines contracts (interfaces) and core data models.
* **Key Components & Classes:** Repository Interfaces, Domain Models, Service Interfaces, SyncStatus
  enum.

### **3.3. :data Module**

* **Purpose & Scope:** Implements repository interfaces. Orchestrates data synchronization via
  SyncEngine, WorkManager workers (InitialAccountSyncWorker, FolderContentSyncWorker,
  ActionUploadWorker, CacheCleanupWorker, etc.), manages delta sync, local search queries.
* **Key Components & Classes:** Default...Repository implementations, SyncEngine.kt.

### **3.4. :core-db Module**

* **Purpose & Scope:** Defines Room database schema, entities (with syncStatus,
  lastAccessedTimestamp, etc.), DAOs, FTS tables for messages.
* **Key Components & Classes:** AppDatabase.kt, Entity classes, DAO interfaces.

### **3.5. :backend-microsoft Module**

* **Purpose & Scope:** Microsoft/Outlook specific logic, MSAL, Microsoft Graph API (including delta
  queries).
* **Key Components & Classes:** MicrosoftAuthManager.kt, GraphApiHelper.kt.

### **3.6. :backend-google Module**

* **Purpose & Scope:** Google/Gmail specific logic, AppAuth, Gmail API (including historyId for
  delta sync).
* **Key Components & Classes:** GoogleAuthManager.kt, GmailApiHelper.kt.

## **4\. Detailed Authentication & Authorization Flows**

*(This section's core logic remains the same, but repositories now save results to the local DB upon
success, triggering initial sync processes.)*

### **4.1. Google OAuth Flow (AppAuth Orchestrated by GoogleAuthManager)**

1. **Sign-In Initiation**: UiActionRequired is emitted.
2. **Authorization Grant**: User grants permissions via Custom Tab.
3. **Token Exchange & Persistence**: Code is exchanged for tokens; on success,
   DefaultAccountRepository saves the new AccountEntity to the database, triggering
   InitialAccountSyncWorker.
4. **Authenticated API Calls**: Ktor client uses token provider to make authenticated calls.
5. **Sign-Out**: Tokens are revoked, and the AccountEntity is deleted from the database, along with
   its cached data.

### **4.2. Microsoft OAuth Flow (MSAL)**

1. **MSAL Initialization**: MSAL client is initialized.
2. **Sign-In Initiation**: MSAL handles the UI flow; on success, DefaultAccountRepository saves the
   new AccountEntity to the database, triggering InitialAccountSyncWorker.
3. **Authenticated API Calls**: Ktor client uses token provider for silent token acquisition.
4. **Sign-Out**: MSAL removes the account, and DefaultAccountRepository deletes the corresponding
   AccountEntity and its cached data.

## **5\. UI Architecture (MVVM & Jetpack Compose)**

* **Pattern:** Offline-First MVVM with a domain layer is strictly followed.
* **ViewModels (androidx.lifecycle.ViewModel):** Scoped to navigation destinations, they hold UI
  state, observe data from Use Cases (sourced from local DB), and manage UI feedback for sync/error
  states.
* **Views (Jetpack Compose Composables):** Observe StateFlow from ViewModels using
  collectAsStateWithLifecycle(). Implement pull-to-refresh, display sync status indicators.
* **State Management:** Immutable data classes represent screen state.
* **Navigation:** Jetpack Navigation for Compose is used with routes like home,
  message\_detail/{messageId}, and settings.
* **Theming:** Material 3 theming with Dynamic Color is supported.

## **6\. Local Search**

* **Technology:** Room FTS4 (or FTS5) on MessageEntity for relevant fields (subject, sender, body
  snippet if stored).
* **User Experience:**
    * Local search results displayed immediately.
    * If online, a concurrent server search is initiated.
    * Online results are clearly distinguished and typically appended.
    * UI indicates if search is offline-only.
* **Data Handling:** Messages fetched from online search and viewed are added to the local cache.

## **7\. Test Strategy**

* **Goals:** Verify correctness, ensure proper interaction, prevent regressions, especially for sync
  logic, cache eviction, and error handling.
* **Scope:**
    * **Unit Tests:** Focus on ViewModels, Repositories (synchronization logic, delta handling,
      cache policy adherence), Use Cases, Mappers, API Helpers, SyncEngine, WorkManager Workers.
    * **Integration Tests:** Verify interactions between Repositories, DAOs, SyncEngine, and
      WorkManager. Test database migrations and FTS functionality.
    * **UI/E2E Tests:** Validate full user flows, including offline scenarios, sync status display,
      error recovery, and search.
* **Tools:** JUnit, MockK, kotlinx-coroutines-test, Turbine, Hilt testing APIs, Robolectric,
  Espresso.

## **8\. Setup, Build, and Development Environment**

* **Prerequisites:** Latest stable Android Studio, SDK, JDK.
* **Project Setup:** Clone repo, sync Gradle, configure client IDs in auth\_config\_msal.json and
  build.gradle.kts.
* **Build Commands:** ./gradlew build, ./gradlew testDebugUnitTest, etc.
* **Development Guidance:** Follow standard Git flow, use Timber for logging. Pay close attention to
  database transaction safety and background task management.