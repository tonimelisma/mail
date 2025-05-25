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
