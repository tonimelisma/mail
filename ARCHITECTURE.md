# **Melisma Mail \- Architectural Design & Developer Guide**

Version: 3.0 (Synthesized)  
Date: June 8, 2025

## **1\. Introduction & Project Vision**

### **1.1. Overview**

Melisma Mail is an Android email client designed to offer a clean, intuitive, and native user experience, adhering to Material 3 design principles. It aims to be a compelling alternative to default email applications, initially supporting Microsoft Outlook and Google (Gmail) accounts, with a focus on modern Android development practices and a robust offline-first architecture.

### **1.2. Core Goals**

* **User Experience:** Provide a seamless, performant, and visually appealing interface that works smoothly online and offline. Ensure users have access to a significant portion of their recent mail data even without connectivity. Download status for message bodies and attachments is clearly indicated with smooth transitions and retry options for errors.  
* **Modern Android Practices:** Leverage Kotlin, Jetpack Compose, Coroutines, Flow, Hilt, and Room.  
* **Security:** Ensure secure handling of user credentials and data, particularly OAuth tokens.  
* **Modularity:** Maintain a well-defined, modular codebase for better maintainability, scalability, and testability.  
* **Data Transparency & Control:** Provide clear, real-time feedback on synchronization status, errors, and network state. Offer users reasonable control over data caching policies.

### **1.3. Key Technologies**

* **UI:** Jetpack Compose  
* **Architecture:** Offline-First MVVM with a Domain Layer (Use Cases). Phase-1/2 sync refactor completed 2025-06-17  
* **Local Storage:** Room (with FTS, FolderSyncStateEntity, and many-to-many relationships)  
* **Asynchronous Programming:** Kotlin Coroutines & Flow  
* **Dependency Injection:** Hilt  
* **Networking:** Ktor (with OkHttp engine)  
* **Authentication:** AppAuth (Google), MSAL (Microsoft)  
* **Security:** Android Keystore via a custom SecureEncryptionService

## **2\. Core Architectural Principles**

### **2.1. Overall Architecture**

Melisma Mail employs a layered architecture designed for an offline-first experience. The key change in v3.0 is the replacement of the distributed SyncEngine/WorkManager model with a centralized SyncController.

* **UI Layer (:mail module):** Responsible for presenting data to the user and handling interactions. It observes data exclusively from the database via ViewModels and Use Cases. It submits SyncJob requests to the data layer and observes the SyncController's state for real-time UI feedback (e.g., global sync status bars, contextual pull-to-refresh messages).  
* **Domain Layer (:domain module):** Contains discrete business logic in the form of single-purpose Use Case classes (e.g., SignInUseCase, MarkAsReadUseCase).  
* **Data Layer (:data module):** The synchronizer between network APIs and the local database. This layer is orchestrated by the central **SyncController** singleton (SyncEngine has been fully retired). Repositories now submit granular `SyncJob`s directly to the controller which manages all data fetching, caching, action queuing, and local search.  
* **Database Layer (:core-db module):** Defines the Room database schema, entities, DAOs, and a many-to-many relationship between messages and folders. It includes a FolderSyncStateEntity to persist the sync state for each folder, making background sync resilient to app restarts.  
* **Backend Layer (:backend-google, :backend-microsoft modules):** Handles all provider-specific API communication, including delta queries.  
* **Contracts Layer (:core-data module):** Defines interfaces (Repositories, Services) and core data models for the application.

### **2.2. Offline-First and Single Source of Truth**

The application is architected to be "offline-first". The UI layer is completely decoupled from the network. It observes Flows directly from the Room database DAOs. The local database is the **single source of truth** for the entire application state. All user mutations are applied to the local database first (optimistic updates), then queued as a SyncJob for synchronization.

### **2.3. Asynchronous Operations & The SyncController**

All data synchronization is managed by the centralized **SyncController**. This singleton runs a continuous loop in its own coroutine scope and is the single orchestrator of data flow between the network and the database. It replaces the previous SyncEngine and distributed WorkManager task architecture.

1. **Central SyncJob Queue:** The controller manages a PriorityBlockingQueue of SyncJob objects. These are lightweight data classes representing a single, discrete task (e.g., fetch a folder's message list, download a message body, upload a "mark as read" action).  
2. **Unified Prioritization Algorithm:** The controller processes jobs according to a strict, multi-level priority hierarchy. It will only process jobs from a lower level if all higher-priority levels are empty.  
   * **Level 1: Golden Rule (Immediate User Need):** Absolute highest priority. For tasks directly initiated by the user, such as performing a pull-to-refresh (`ForceRefreshFolder`), fetching a message body to view (`FetchFullMessageBody`), or loading the next page of a list they are actively scrolling (`FetchNextMessageListPage`). These jobs interrupt any ongoing background work.
   * **Level 2: Fulfilling User Intent:** High priority. For uploading queued actions like sending, deleting, or moving messages (`UploadAction`). This ensures user-initiated changes are synced with the server promptly.  
   * **Level 4: Background Freshness & Backfill:** Lowest priority. This is the "Self-Perpetuating Queue" model for keeping the cache up-to-date. It runs only when no other work is pending and is fed by the polling mechanisms.
3. **Continuous Polling Lifecycle:** The `SyncController`'s processing loop is always active. The application's state (foreground/background) dictates the polling strategy for queueing new low-priority jobs.  
   * _(Implemented 2025-06-19)_  
   * **Active Polling (Foreground):** An aggressive 5-second timer queues freshness jobs for critical folders.  
   * **Passive Polling (Background):** A battery-conscious WorkManager job queues freshness jobs approximately every 15 minutes.
4. **Self-Perpetuating Queue (Background Sync):** The SyncController intelligently backfills the cache. When a page of messages is fetched (as a Level 4 job) and the server indicates more pages are available, a new low-priority SyncJob for the next page is automatically created and added to the queue. The nextPageToken for this process is persisted in the FolderSyncStateEntity table, making the process resilient. This replaces the need for FolderContentSyncWorker.  
5. **Observable State:** The SyncController exposes its real-time status (e.g., isSyncing, networkAvailable, errorState) via a StateFlow\<SyncStatus\>. This allows the UI to provide a transparent user experience with global status bars, contextual feedback on pull-to-refresh, and detailed diagnostic panels.

### **2.4. Data Caching and Eviction**

* **Attachment Storage & Backup Exclusion:** All downloaded attachments **must** be stored in a dedicated attachments/ directory within the application's no\_backup directory. The application's backup configuration rules **must** exclude the entire Room database directory and this attachments/ directory to comply with platform best practices.  
* **User-Configurable Policies:** Users can configure cache size limits and download preferences for message bodies and attachments via a SettingsScreen and UserPreferencesRepository.  
* **Accurate Size Tracking:** The system tracks the size of cached data via AttachmentEntity.size and MessageBodyEntity.sizeInBytes to enforce limits accurately.  
* **Eviction Policy:** A dedicated SyncJob for cache cleanup is triggered periodically or when the total cache size exceeds the user-configured limit. The policy evicts data to bring usage down to 80% of the limit.  
  * **Exclusions:** Items with a pending upload/download status, messages in the outbox, and any message (including its body and attachments) accessed within the last 90 days (tracked via lastAccessedTimestamp) are protected from eviction.  
  * **Priority:** It evicts the least recently used data first, prioritizing the removal of attachments, then message bodies, and finally message headers.
  * _(Implemented 2025-06-25: See SyncController.runCacheEviction.)_

### **2.5. Error Handling and UX**

* **Transparency:** The UI uses the SyncController's observable state to provide clear, real-time indicators for sync status and errors.  
* **Throttling:** The SyncController will manage per-account exponential backoff for API throttling, pausing jobs for a specific account without blocking the entire queue.  
* **Retries:** For transient network/server issues, actions are automatically retried. For persistently failed actions (e.g., sending an email), they will be clearly marked in the UI (e.g., in the Outbox) with a manual retry option.
* **Conflict Resolution:** The current conflict resolution strategy is "last write wins" and is considered sufficient for this stage of development.

### **2.6. Local Search**

* **Technology:** Room FTS5 on MessageEntity for relevant fields (subject, sender, body snippet).  
* **User Experience:** Local search results are displayed immediately. If the device is online, the SyncController concurrently executes a high-priority (Level 1\) SyncJob for a server-side search. Online results are clearly distinguished from local results.

### **2.7. Concurrency**

* **Concurrency:** One active network operation per account at any given time, enforced via an in-memory mutex in `SyncController` _(implemented 2025-06-20)_.  

## **3\. Test Strategy**

* **Goals:** Verify correctness, ensure proper interaction, and prevent regressions, with a strong focus on the SyncController logic, prioritization, caching, and error handling.  
* **Scope:**  
  * **Unit Tests:** Focus on ViewModels, Repositories, Use Cases, Mappers, and especially the SyncController's internal logic and job processing.  
  * **Integration Tests:** Verify interactions between the SyncController, Repositories, and DAOs. Test database migrations thoroughly.  
  * **UI/E2E Tests:** Validate full user flows, including offline scenarios, sync status display, error recovery, and search.  
* **Tools:** JUnit, MockK, kotlinx-coroutines-test, Turbine, Hilt testing APIs, Robolectric, Espresso.