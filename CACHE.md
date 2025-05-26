# Melisma Mail - Caching & Offline-First Implementation Guide

**Version:** 1.0
**Date:** May 25, 2025

## 0. Introduction

This document provides the architectural roadmap for evolving Melisma Mail into an offline-first
application. It outlines the necessary changes to implement a robust local caching layer using Room
and a sophisticated synchronization strategy using WorkManager.

This work should begin **after** the new UI layer (based on Jetpack Navigation) is in place for
viewing individual messages and marking them as read/unread, as this provides the necessary UI to
test the caching functionality.

## I. Core Architectural Changes

The foundation of this effort is to make a local database the "single source of truth" for the app.
The UI will only ever read from this database. The network will only be used to sync this database.

### 1. New Module: `:core-db`

A new Android library module must be created to hold all database-related code.

* **Dependencies:** Room (KSP), Kotlin Serialization (for type converters).
* **Components:**
    * **Entities:** Create `@Entity` data classes for all persisted data.
        * `AccountEntity`
        * `FolderEntity`
        * `MessageEntity` (with fields for `subject`, `sender`, `snippet`, `timestamp`, `isRead`,
          `isStarred`, etc.)
        * `MessageBodyEntity` (One-to-one with `MessageEntity`, holds the full body, fetched on
          demand).
        * `AttachmentEntity` (One-to-many with `MessageEntity`, holds metadata and a local file path
          if downloaded).
        * `MessageFtsEntity` (A virtual FTS5 table for fast, local, full-text search on messages).
    * **DAOs (Data Access Objects):** Create `@Dao` interfaces.
        * UI-facing queries must return a `Flow` (e.g.,
          `fun getMessagesForFolder(folderId: String): Flow<List<MessageEntity>>`). Room will ensure
          the UI automatically updates when data changes.
        * Include methods for bulk `INSERT`, `UPDATE`, and `DELETE` (
          `@Insert(onConflict = OnConflictStrategy.REPLACE)` is very useful).
    * **Database Class:** Create an `AppDatabase` class extending `RoomDatabase`.

### 2. Refactoring the `:data` Layer Repositories

The role of your `Default...Repository` classes will fundamentally change from simple API data
fetchers to **synchronizers**.

* **Dependencies:** They will now inject both the `MailApiService` (for network) and the relevant
  DAOs from `:core-db` (for local storage).
* **New Logic:**
    * The `Flow` exposed to use cases will now come directly from the DAO (e.g.,
      `folderDao.getFolders()`).
    * New `suspend fun sync...()` methods will be created. For example,
      `DefaultFolderRepository.syncFolders(accountId: String)` will:
        1. Call `mailApiService.getFolders()`.
        2. Call `folderDao.insertAll()` to save the results to the database. Room's `onConflict`
           strategy will handle updates.

## II. Phased Implementation Plan

### Phase 1: Foundation (Database & Repository Refactor)

1. **Create the `:core-db` module** with the `AppDatabase`, entities, and DAOs.
2. **Refactor one flow:** Start with folders. Modify `DefaultFolderRepository` to save API results
   to the `FolderDao` and have `GetFoldersForAccountUseCase` consume the `Flow` from the DAO.
3. **Update the UI:** Ensure the `ModalNavigationDrawer` in the `HomeScreen` correctly observes the
   folder list from the database via the ViewModel and Use Case.

### Phase 2: Initial & On-Demand Sync

1. **Implement On-Demand Fetch:** Refactor the `MessageDetailViewModel`. When a user taps a message,
   it should first check if the full body and attachments are in the DB. If not, trigger a
   `WorkspaceMessageDetailsUseCase` which calls the API, saves the body/attachments to the DB, and
   the UI will update automatically.
2. **Implement Attachment Policy:**
    * Inline images (often used in email bodies) and other files are all considered attachments.
    * Create user settings for:
        * "Automatically download attachments smaller than:" (e.g., 1 MB, 5 MB).
        * "Total attachment cache size:" (e.g., 500 MB).
    * The `WorkspaceMessageDetailsUseCase` will respect these settings.
3. **Implement Initial Sync:**
    * Create a `SyncWorker` class using `WorkManager`.
    * Create a `SyncAccountUseCase` that enqueues a `OneTimeWorkRequest` when a new account is
      added.
    * The `SyncWorker` will execute the bulk download: fetches all folders, then fetches recent
      message headers (respecting the "Sync emails from the past" setting), and any attachments that
      meet the auto-download criteria.

### Phase 3: Background & Foreground "Smart Sync"

1. **Implement Background Sync:**
    * Use `WorkManager` to schedule a `UniquePeriodicWork`.
    * The `SyncWorker` will perform a lightweight "delta" sync, fetching only new items since the
      last sync time to conserve battery and data.
2. **Implement Lifecycle-Aware Smart Sync:**
    * In the `HomeViewModel`, use a `LifecycleObserver` or `viewModelScope` to detect when the UI is
      in the foreground.
    * When the app is active, trigger the `SyncWorker` on an expedited, short-interval schedule (
      e.g., every minute).
    * When the app is backgrounded, cancel the frequent polling to save resources. The less frequent
      periodic sync will take over.
3. **Implement Offline Outbox:**
    * Create an `OutboxEntity` in your Room database.
    * When a user sends an email, the `SendEmailUseCase` will save it to the Outbox table first.
    * A separate `OutboxWorker` (using `WorkManager` with a network constraint) will be responsible
      for reading from this table and attempting to send the emails whenever the device is online.

## III. Detailed Implementation Steps - Phase 1: Folders Foundation

This section outlines the concrete steps taken to implement the first phase of local caching,
focusing on user accounts and their mail folders.

1. **Created the `:core-db` module:**
    * Added an Android library module named `core-db`.
    * Configured `settings.gradle.kts` to include `:core-db`.
    * Created `core-db/build.gradle.kts` with dependencies for Room (KSP, runtime, ktx), Kotlin
      Coroutines, and Hilt.

2. **Defined Entities in `:core-db`:**
    * `core-db/src/main/java/net/melisma/core_db/entity/AccountEntity.kt`:
        * `@PrimaryKey id: String` (from `Account.id`)
        * `username: String`
        * `emailAddress: String` (derived from `Account.username`)
        * `providerType: String` (from `Account.providerType`)
    * `core-db/src/main/java/net/melisma/core_db/entity/FolderEntity.kt`:
        * `@PrimaryKey id: String` (from `MailFolder.id`)
        * `accountId: String` (foreign key to `AccountEntity.id`)
        * `displayName: String`
        * `totalItemCount: Int`
        * `unreadItemCount: Int`
        * `type: WellKnownFolderType` (from `MailFolder.type`)
        * Includes `@ForeignKey` to `AccountEntity` with `onDelete = CASCADE`.
        * Includes `@Index` on `accountId`.

3. **Created TypeConverters in `:core-db`:**
    * `core-db/src/main/java/net/melisma/core_db/converter/WellKnownFolderTypeConverter.kt`:
        * Handles conversion between `WellKnownFolderType` enum and `String` for Room storage.

4. **Created DAOs (Data Access Objects) in `:core-db`:**
    * `core-db/src/main/java/net/melisma/core_db/dao/AccountDao.kt`:
        * `insertOrUpdateAccount(account: AccountEntity)`
        * `insertOrUpdateAccounts(accounts: List<AccountEntity>)`
        * `getAccountById(accountId: String): Flow<AccountEntity?>`
        * `getAllAccounts(): Flow<List<AccountEntity>>`
        * `deleteAccount(accountId: String)`
        * `getAnyAccount(): AccountEntity?`
    * `core-db/src/main/java/net/melisma/core_db/dao/FolderDao.kt`:
        * `insertOrUpdateFolders(folders: List<FolderEntity>)`
        * `getFoldersForAccount(accountId: String): Flow<List<FolderEntity>>`
        * `deleteAllFoldersForAccount(accountId: String)`
        * `clearAllFolders()`

5. **Created Database Class in `:core-db`:**
    * `core-db/src/main/java/net/melisma/core_db/AppDatabase.kt`:
        * `@Database` for `AccountEntity`, `FolderEntity`. Version 1. `exportSchema = false`.
        * Annotated with `@TypeConverters(WellKnownFolderTypeConverter::class)`.
        * Abstract methods for `accountDao()` and `folderDao()`.
        * Companion object with singleton `getDatabase(context: Context)` method.

6. **Created DI Module for Database in `:core-db` (Hilt):**
    * `core-db/src/main/java/net/melisma/core_db/di/DatabaseModule.kt`:
        * `@Module @InstallIn(SingletonComponent::class)`.
        * Provides `@Singleton AppDatabase`, `@Singleton AccountDao`, `@Singleton FolderDao`.

7. **Integrated `:core-db` into `:data` module:**
    * Added `implementation(project(":core-db"))` to `data/build.gradle.kts`.

8. **Created Mappers in `:data` module:**
    * `data/src/main/java/net/melisma/data/mapper/AccountMappers.kt`:
        * `fun Account.toEntity(): AccountEntity`
        * `fun AccountEntity.toDomainAccount(): Account` (maps to `core_data.model.Account`)
    * `data/src/main/java/net/melisma/data/mapper/FolderMappers.kt`:
        * `fun MailFolder.toEntity(accountId: String): FolderEntity` (takes
          `core_data.model.MailFolder`)
        * `fun FolderEntity.toDomainModel(): MailFolder` (maps to `core_data.model.MailFolder` which
          serves as the domain model for folders for now)

9. **Refactored `DefaultFolderRepository` in `:data:module`**
    * **Injected DAOs**: `AccountDao` and `FolderDao` are injected via Hilt.
    * **`observeDatabaseChanges()` (new private method)**:
        * Observes `accountDao.getAllAccounts()`.
        * Uses `flatMapLatest` to get `folderDao.getFoldersForAccount()` for each account.
        * Uses `combine` to merge these folder flows into a `Map<String, List<FolderEntity>>`.
        * Maps `FolderEntity` to `MailFolder` (domain model).
        * The combined flow updates
          `_folderStates: MutableStateFlow<Map<String, FolderFetchState>>`.
        * Handles DB errors by emitting `FolderFetchState.Error`.
        * Manages merging DB states with potential ongoing sync (Loading/Error) states.
    * **`observeFoldersState(): Flow<Map<String, FolderFetchState>>`**:
        * Now returns `_folderStates.asStateFlow()`, which is populated by `observeDatabaseChanges`
          and sync operations.
    * **`manageObservedAccounts(accounts: List<Account>)`**:
        * Compares incoming `accounts` with those in `AccountDao`.
        * Adds new accounts to DB (
          `accountDao.insertOrUpdateAccounts(accounts.map { it.toEntity() })`).
        * Removes accounts no longer present from DB (`accountDao.deleteAccount`,
          `folderDao.deleteAllFoldersForAccount`).
        * Cancels ongoing syncs for removed accounts.
        * Triggers `refreshFoldersForAccountInternal` for accounts that need an initial folder
          sync (e.g., new accounts, or those with empty/error states).
    * **
      `refreshFoldersForAccountInternal(account: Account, activity: Activity?, forceRefresh: Boolean, reasonSuffix: String)` (
      new private method)**:
        * Manages a `syncJobs: ConcurrentHashMap<String, Job>` to prevent concurrent syncs for the
          same account unless `forceRefresh` is true.
        * Sets `_folderStates` to `FolderFetchState.Loading` for the account.
        * Calls the appropriate `mailApiService.getMailFolders()`.
        * On success: maps API response (`List<MailFolder>`) to `List<FolderEntity>` and saves to
          `folderDao.insertOrUpdateFolders()`. The DB observation flow then naturally updates
          `_folderStates` to `Success`.
        * On failure (API or other exception): updates `_folderStates` to `FolderFetchState.Error`.
        * Ensures sync job is removed from `syncJobs` upon completion or cancellation.
    * **`refreshFoldersForAccount(accountId: String, activity: Activity?)` (public)**:
        * Fetches `AccountEntity` from `accountDao`.
        * Converts to domain `Account`.
        * Calls `refreshFoldersForAccountInternal`.
    * **`refreshAllFolders(activity: Activity?)` (public)**:
        * Fetches all `AccountEntity`s from `accountDao`.
        * Iterates and calls `refreshFoldersForAccountInternal` for each supported account.
    * Removed old `launchFolderFetchJob` and related mechanisms like `observedAccounts` map. Sync
      state is now primarily managed via `syncJobs` and reflected into `_folderStates`.

This completes the initial database setup and refactoring of the folder data flow to use the local
cache as the primary source of truth, with network sync on demand.

## IV. Current State, Lessons, and Architectural Impact (Post-Phase 1)

### A. Current State (Folders Implemented)

* **Functionality:** User accounts and their mail folders are now cached locally using Room.
  The `DefaultFolderRepository` fetches folder lists from the network, stores them in the local
  database, and serves data to the UI primarily from this database.
  The `MainViewModel` observes folder states, which now originate from database flows, automatically
  reflecting updates from sync operations.
  Sync operations (refresh per account, refresh all) are in place, updating the local DB which in
  turn updates the UI.
* **Build Status:** The project compiles successfully after resolving KSP version issues, missing
  module dependencies, and minor code errors.
  The warning for `ExperimentalCoroutinesApi` (due to `flatMapLatest`) has been addressed with
  `@OptIn`.

### B. Mistakes, Shortcuts, and Lessons Learned

* **Initial Build Errors & Versioning:** Encountered several build issues primarily due to:
    * Incorrect Gradle plugin aliases (Hilt).
    * Missing library definitions in `libs.versions.toml` for Room, Coroutines, and SDK versions (
      initially tried to reference non-existent catalog versions for SDKs).
    * KSP errors (`unexpected jvm signature V`, KSP task creation failures) which required iterating
      through Kotlin, KSP plugin, and AGP versions to find a compatible set. The final working
      combination seems to be AGP `8.9.3`, Kotlin `2.1.10`, KSP `2.1.10-1.0.29`, and Room `2.7.1` (
      as per your latest update).
    * Missing module dependency: `:core-db` needed an `implementation` dependency on `:core-data`
      because entities/converters in `:core-db` used models from `:core-data`.
* **Shortcut - Domain Model for Folders:** Currently, `net.melisma.core_data.model.MailFolder` (the
  API model) is directly used as the "domain model" that `FolderEntity` maps to and is exposed by
  the repository. Ideally, a distinct `DomainFolder` model in the `:domain` layer would provide
  better separation, but for this increment, reusing `MailFolder` was a shortcut to avoid immediate
  changes in ViewModels and UseCases consuming it.
* **Complex Flow in Repository:** The `observeDatabaseChanges` logic in `DefaultFolderRepository`
  involving `flatMapLatest` and `combine` to merge multiple account folder flows and then merge this
  with transient sync states (`Loading`, `Error`) is quite complex. While functional, it needs
  careful testing for edge cases.
* **Error Handling in DB Flow:** The `catch` block in `observeDatabaseChanges` for DB errors
  currently emits a generic `FolderFetchState.Error`. More granular error handling or state
  representation for DB issues might be needed.
* **`ensureActive()`:** This was initially removed and then re-added. It's crucial for ensuring
  coroutines honor cancellation during long-running operations like network calls.

### C. Architectural Updates & Impact

* **New Module `:core-db`:** Successfully introduced, encapsulating all Room database components (
  entities, DAOs, database class, type converters, DI module for DB).
* **Repository as Synchronizer (`DefaultFolderRepository`):** The repository's role has shifted. It
  now orchestrates data flow between the network (`MailApiService`) and the local database (
  `FolderDao`, `AccountDao`), with the database being the primary source for observers.
* **Single Source of Truth (for Folders):** For folder data, the app now moves closer to the SSoT
  principle, where UI-bound data flows originate from the database.
* **Reactive UI Updates:** Changes to folder data in the database (e.g., after a network sync)
  automatically propagate to the UI via `Flow` objects.
* **Dependency Changes:**
    * `:core-db` depends on `:core-data` (for `WellKnownFolderType`).
    * `:data` depends on `:core-db` (for DAOs) and `:core-data`.

## V. Phase 2a: Caching Message Headers for One Folder

This phase focuses on caching message headers for a selected folder, allowing the UI to display
messages from the local database and providing a mechanism to sync these messages with the network.

### A. Summary of Work Done (Phase 2a Implementation)

1. **`core-db` Module (Entities, DAO, Converters, Database):**
    * Created `MessageEntity.kt` with fields aligned with `core_data.model.Message` and necessary
      foreign keys to `AccountEntity` and `FolderEntity`. Includes indices for common queries.
    * Created `StringListConverter.kt` using Kotlinx Serialization for `List<String>` type fields (
      e.g., `recipientAddresses`, `ccAddresses`, `bccAddresses`).
    * Updated `AppDatabase.kt`:
        * Added `MessageEntity::class` to the `@Database` entities list.
        * Added `StringListConverter::class` to `@TypeConverters`.
        * Incremented database `version` to 2.
        * Added `fallbackToDestructiveMigration()` for development.
        * Added `abstract fun messageDao(): MessageDao`.
    * Created `MessageDao.kt` with methods for inserting, querying (by folder, by ID), deleting (by
      folder, by account), and a transactional clear-and-insert.
    * Updated `core-db/di/DatabaseModule.kt` to provide `MessageDao`.
    * Added Kotlin Serialization plugin and `kotlinx-serialization-json` dependency to
      `core-db/build.gradle.kts`.

2. **`core-data` Module (Models, Repository Interface):**
    * Enhanced `core_data.model.Message.kt` to include fields that were previously only in
      `MessageEntity` (e.g., `senderName`, `recipientNames`, `isStarred`, `hasAttachments`,
      `sentDateTime`, `sentTimestamp`), promoting alignment between domain model and cache entity.
    * Created `core_data.model.MessageSyncState.kt` (`Idle`, `Syncing`, `SyncSuccess`, `SyncError`)
      for folder message synchronization status.
    * Updated `core_data.repository.MessageRepository.kt` interface:
        * Replaced `messageDataState` with `messageSyncState: StateFlow<MessageSyncState>`.
        * Added
          `observeMessagesForFolder(accountId: String, folderId: String): Flow<List<Message>>`.
        * Added `setTargetFolder(account: Account?, folder: MailFolder?)`.
        * Added
          `syncMessagesForFolder(accountId: String, folderId: String, activity: Activity? = null)`.
        * Updated other method signatures as needed (e.g., `sendMessage`, `getMessageAttachments`).
    * Updated `core_data.repository.AccountRepository.kt` to include
      `getAccountById(accountId: String): Flow<Account?>`.

3. **`data` Module (Mappers, Repository Implementation):**
    * Created `data/mapper/MessageMappers.kt` with `Message.toEntity()` and
      `MessageEntity.toDomainModel()`, handling timestamp conversions.
    * Refactored `data/repository/DefaultMessageRepository.kt`:
        * Injected `MessageDao` and `AccountRepository`.
        * Implemented `messageSyncState` and `observeMessagesForFolder` (reads from DAO).
        * Implemented `setTargetFolder` (manages current target, sync jobs) and
          `syncMessagesForFolderInternal` (fetches from API, saves to DAO, updates sync state).
        * Updated `refreshMessages`, `markMessageRead` (to also update DB).
        * Updated methods like `getMessageDetails` to use `accountRepository.getAccountById()`.
        * Added stubs for `createDraftMessage`, `updateDraftMessage`, `searchMessages`.
    * Updated `data/repository/DefaultAccountRepository.kt`:
        * Injected `AccountDao` and implemented `getAccountById()`. Marked `getAccounts()` with
          `TODO` for DB as SSoT.

4. **`domain` Module (Use Cases):**
    * Updated `MoveMessageUseCase.kt` for new `moveMessage` signature.
    * Refactored `ObserveMessagesForFolderUseCase.kt` to directly return
      `messageRepository.observeMessagesForFolder()`.

5. **`app` Module (ViewModel, UI):**
    * Updated `MainViewModel.kt`:
        * `MainScreenState` now uses `messages: List<Message>` and
          `messageSyncState: MessageSyncState`.
        * Split message observation: `observeMessageRepositorySyncState()` for sync status, and
          `observeSelectedFolderAndMessages()` for collecting messages from
          `messageRepository.observeMessagesForFolder()` based on selection.
        * Updated `onFolderSelected()` and `refreshMessages()` to align with new repository methods.
    * Updated `app/ui/MainAppScreen.kt` and `app/ui/MessageListContent.kt` to use the new
      `MainScreenState` fields and `MessageListContent` signature.
    * Corrected string resource usage in `MessageListContent.kt`.

6. **`backend-microsoft` Module:**
    * Implemented `getAccountById` in `MicrosoftAccountRepository.kt`.

### B. Current Project State (Post-Phase 2a)

* **Functionality:** Message headers for a single selected folder are cached. The UI observes these
  messages from the database. Selecting a folder or refreshing triggers a network sync that updates
  the local DB. `MessageSyncState` indicates sync status.
* **Build Status:** The project compiles successfully.
* **Vision Alignment:** This phase aligns with the "Offline-First" vision by:
    * Reading message lists primarily from the local Room database (`MessageDao`).
    * The UI (`MainViewModel`, `MessageListContent`) observes this local data.
    * Network operations (`DefaultMessageRepository.syncMessagesForFolderInternal`) happen to update
      the cache, and the UI reacts to cache changes.

### C. Shortcuts, Workarounds, Technical Debt, and Questionable Choices

* **`fallbackToDestructiveMigration()`:** Used in `AppDatabase.kt`. **Debt:** Production apps need
  proper `Migration` objects (e.g., `MIGRATION_1_2` for adding `MessageEntity`).
* **Error Handling in Mappers:** `MessageMappers.toEntity` uses `try-catch` for date parsing with a
  default to `System.currentTimeMillis()`. **Debt/Smell:** Better API contract or error reporting
  needed. Potential for silent data inconsistencies if parsing fails often for individual items.
* **API-Entity-Model Discrepancies:** `core_data.model.Message` was enhanced, but the
  `MailApiService` might not provide all new fields. Mappers default these, leading to potentially
  incomplete cached data. **Debt:** API and DTOs need to be updated to provide all fields.
* **`MailApiService` Stubs:** Methods like `getMessageAttachments` in `DefaultMessageRepository` are
  stubbed as they don't exist in `MailApiService` yet. **Debt:** API service needs implementation.
* **`DefaultAccountRepository.getAccounts()` SSoT:** Still marked with `TODO // SSoT from DB?`. *
  *Debt:** Needs refactor for accounts to be truly cache-first.
* **Sync Transactionality:** `MessageDao.clearAndInsertMessagesForFolder` is transactional, but the
  broader sync in `DefaultMessageRepository` (API call + DAO ops) isn't. **Smell:** Potential for
  inconsistent state if app crashes mid-sync.
* **No Paging:** Message lists are fetched/displayed in full (up to API limit). **Debt:**
  Inefficient for large folders. Paging 3 is essential.
* **Limited Offline Write/Outbox:** Most write operations (send, delete, move) are API-direct or
  stubs. `markMessageRead` updates DB locally after API success, a step in the right direction. *
  *Debt:** Full offline write capability with an outbox pattern is missing.
* **Log Spam:** Numerous debug logs should be reviewed for release builds.

### D. Plan for Next Increment (Phase 2b - True Cache-First for Messages & Paging)

This plan focuses on making the message list truly cache-first using Paging 3 and addressing some
immediate debt. The vision is for the UI to be seamlessly fed by the local cache, with the network
operating in the background to fill and update this cache.

1. **Integrate Jetpack Paging 3 for Message List:**
    * **Goal:** Implement "infinite scrolling" for messages, loading from DB first, then fetching
      from network when DB runs out, aligning with "Scenario B: Scrolling Past Cached Data."
    * **`core-db/MessageDao.kt`:** Add
      `getMessagesPagingSource(accountId: String, folderId: String): PagingSource<Int, MessageEntity>`.
    * **`data/repository/DefaultMessageRepository.kt`:**
        * Implement
          `getMessagesPager(accountId: String, folderId: String, pagingConfig: PagingConfig): Flow<PagingData<Message>>`.
        * Uses `Pager` with `pagingSourceFactory = { messageDao.getMessagesPagingSource(...) }` and
          a new `MessageRemoteMediator`.
    * **`data/paging/MessageRemoteMediator.kt` (New File):**
        * **Purpose:** Bridge between database and network for Paging 3.
        * **`load()` method:** Fetches pages from `MailApiService`, saves to `MessageDao`. Handles
          `LoadType.REFRESH` (clear and insert) vs. `APPEND` (insert). Returns
          `MediatorResult.Success` or `Error`.
        * **`initialize()` method:** Consider `InitializeAction.LAUNCH_INITIAL_REFRESH`.
    * **`core-data/repository/MessageRepository.kt`:** Add `getMessagesPager(...)` interface method.
    * **`app/MainViewModel.kt`:**
        * Replace `messages: List<Message>` with `val messagesPagerFlow: Flow<PagingData<Message>>`.
        * Initialize this flow using
          `messageRepository.getMessagesPager(...).cachedIn(viewModelScope)`.
    * **`app/ui/MainAppScreen.kt` & `MessageListContent.kt`:**
        * Adapt to use `LazyPagingItems<Message>` collected from `messagesPagerFlow`.
        * Use `items(lazyPagingItems)` in `LazyColumn`.
        * Handle loading/error states from `lazyPagingItems.loadState`.
        * `PullToRefreshBox` calls `lazyPagingItems.refresh()`.

2. **Refine `MessageSyncState` Handling (Post-Paging Integration):**
    * **Goal:** Ensure `MessageSyncState` accurately reflects `RemoteMediator` operations.
    * **`MessageRemoteMediator`:** Update `_messageSyncState` in `DefaultMessageRepository` from
      within `load()` to reflect current sync activity.
    * **UI:** Primarily react to `PagingData` and `LoadState`. `MessageSyncState` for supplemental
      global indicators/errors.

3. **Address `fallbackToDestructiveMigration()`:**
    * **Goal:** Implement proper Room Migrations for data persistence.
    * **`core-db/AppDatabase.kt`:**
        * Define `MIGRATION_1_2` for adding `MessageEntity`.
        * Replace `.fallbackToDestructiveMigration()` with `.addMigrations(MIGRATION_1_2)`.

4. **Improve `DefaultAccountRepository` SSoT for Accounts:**
    * **Goal:** Make `AccountDao` the Single Source of Truth for accounts.
    * **`DefaultAccountRepository.kt`:** Refactor `getAccounts()` to read from DAO. Sync logic (
      MSAL, Google) updates `AccountEntity` in DB.

5. **True Offline for `markMessageRead` (and other simple state changes):**
    * **Goal:** Write to DB immediately, then queue network sync, aligning with "Scenario A: Reading
      a Cached Message" (for state changes).
    * **`DefaultMessageRepository.markMessageRead()`:**
        1. Update `isRead` in local `MessageEntity` via `MessageDao` *first*.
        2. Queue a background task (e.g., WorkManager one-off task) to sync this specific change to
           the API. This task should have retry logic.

        * This makes the UI update instantly and decouples it from network success for state
          changes.

This next increment will significantly advance the offline capabilities and user experience by
providing a robust, paginated, cache-first message list.
