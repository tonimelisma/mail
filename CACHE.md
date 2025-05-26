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
