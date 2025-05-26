# **Melisma Mail \- Caching & Offline-First Implementation Guide**

Version: 1.2  
Date: May 26, 2025

## **0\. Vision & Introduction**

This document outlines the architecture for Melisma Mail as an **offline-first application**. The
core principle is that the local Room database is the **Single Source of Truth (SSoT)** for all
application data. The UI reads exclusively from the database, ensuring a responsive and consistent
experience, even when offline. Network operations are treated as synchronization tasks that update
this local database.

## **I. Core Architecture**

* **:core-db (The Foundation)**: This module contains the Room database (AppDatabase), data
  entities (e.g., AccountEntity, MessageEntity), and Data Access Objects (DAOs). It is the heart of
  our local persistence strategy.
* **:data (The Orchestrator)**: Repositories in this layer (e.g., DefaultAccountRepository) are not
  simple API fetchers. They are **synchronizers** that manage data flow between the network APIs (
  MailApiService) and the local DAOs. They expose data to the rest of the app by reading directly
  from the DAOs.
* **:backend-google & :backend-microsoft (The Connectors)**: These modules handle the specifics of
  each mail provider's authentication and API. Upon successful authentication or data fetching, they
  pass models back to the :data layer to be persisted.
* **:domain & :app (The Consumers)**: The domain layer contains business logic (UseCases) and the
  app layer contains the UI (ViewModels, Composables). They are completely decoupled from the
  network, consuming data only from the repositories, which in turn source it from the local
  database.

## **II. Implementation Progress (As of May 26, 2025\)**

The project has made significant progress towards the offline-first vision.

* **Phase 1: Database Foundation Complete**
    * The :core-db module has been created with AppDatabase, AccountEntity, and FolderEntity.
    * DefaultFolderRepository has been refactored to fetch folder lists from the network, save them
      to FolderDao, and expose a Flow from the DAO to the UI.
* **Phase 2a: Message Header Caching Complete**
    * MessageEntity and MessageDao have been added to :core-db.
    * DefaultMessageRepository now caches message headers for a selected folder. The UI observes
      these messages from the database, and syncs with the network on demand.
* **Phase 2b: Paging & SSoT for Accounts (Partially Implemented, Build Pending Fixes)**
    * **Paging 3 Foundation**: The message list has been refactored to use Jetpack Paging 3\. This
      includes:
        * A PagingSource in MessageDao.
        * A MessageRemoteMediator in the :data layer to bridge the database and network.
        * The MainViewModel and UI Composables (MessageListContent, MainAppScreen) have been updated
          to use LazyPagingItems and PagingData.
    * **SSoT for Accounts**: DefaultAccountRepository has been refactored to use AccountDao as the
      Single Source of Truth for the getAccounts() method. Sign-in and sign-out operations now
      correctly write to and delete from the database.
    * **Database Migrations**: A proper migration path has been established in AppDatabase, and
      fallbackToDestructiveMigration() has been removed. MIGRATION\_1\_2 (adding Messages) and
      MIGRATION\_2\_3 (adding needsSync column) are now defined.
    * **Build Status**: The project is **currently not building** due to Hilt dependency injection
      errors. Specifically, Hilt cannot resolve CoroutineDispatcher for DefaultAccountRepository.
      This is symptomatic of missing providers for CoroutineScope and a qualified @MicrosoftRepo
      AccountRepository, and potentially an incorrect request for an unqualified CoroutineDispatcher
      in DefaultAccountRepository's constructor.

## **III. Known Technical Debt & Shortcuts**

This section consolidates all known shortcuts and areas needing improvement.

* **API-Model Discrepancies**: The core\_data.model.Message was enhanced to align with the database
  entity, but the backing MailApiService might not provide all the new fields. Mappers currently use
  default values, which could lead to incomplete cached data.
* **Stubbed API Services**: Methods like getMessageAttachments in DefaultMessageRepository are
  stubbed because they don't exist in MailApiService yet.
* **Sync Transactionality**: The broader sync process (API call \+ DAO operations) is not fully
  transactional. An app crash mid-sync could leave the DB in an inconsistent state.
* **Error Handling in Mappers**: Date parsing in MessageMappers uses a try-catch block that defaults
  to the current time on failure. This can cause silent data corruption and should be replaced with
  more robust error reporting.
* **Limited Offline Writes**: While markMessageRead has been updated to be DB-first, other write
  operations (delete, move, send) are still API-direct or stubs. A full offline outbox pattern is
  needed.
* **Log Spam**: The codebase contains numerous debug logs that should be reviewed and removed or
  guarded for release builds.
* **Simplified RemoteMediator**: The current MessageRemoteMediator uses a simulated API call and a
  simplified paging key strategy. It needs to be updated to work with the real MailApiService
  pagination support.
* **Paging Dependencies**: The project required careful addition of Paging 3 dependencies (
  paging-runtime, paging-common, room-paging) across :core-data and :core-db modules to resolve KSP
  and compilation errors.

## **IV. Next Manageable Increment**

The immediate priority is to get the project building successfully.

1. **Fix Dependency Injection Build Errors (CRITICAL FIRST STEP):**
    * **Goal:** Resolve the Hilt errors preventing the project from compiling.
    * **Task 1.1**: In core-data/src/main/java/net/melisma/core\_data/di/DispatchersModule.kt, add a
      Hilt @Provides function for CoroutineScope. This scope should use a SupervisorJob and the
      existing IO CoroutineDispatcher.
    * **Task 1.2**: Create a new Hilt module in the :backend-microsoft module (e.g.,
      backend-microsoft/src/main/java/net/melisma/backend\_microsoft/di/MicrosoftRepositoryModule.kt).
      This module should @Binds your concrete MicrosoftAccountRepository implementation to the
      AccountRepository interface when qualified with @MicrosoftRepo.
    * **Task 1.3**: Verify and correct the constructor of
      data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt. Ensure it requests
      CoroutineDispatcher using the @Dispatcher(MailDispatchers.IO) qualifier, matching how it's
      provided in DispatchersModule.kt.
    * **Outcome**: Project compiles successfully.
2. **Complete Paging 3 Integration & Refine Sync State:**
    * **Goal:** Make the "infinite scroll" message list fully functional and stable.
    * **Task 2.1**: Replace the simulated API call in MessageRemoteMediator with a real
      implementation that uses pagination keys/tokens from the MailApiService. This requires
      verifying the API's pagination capabilities.
    * **Task 2.2**: Harmonize MessageSyncState with Paging's LoadState. Decide if the custom
      MessageSyncState is still needed for global UI elements or if LoadState is sufficient for all
      UI feedback.
    * **Task 2.3**: Thoroughly test the pull-to-refresh (lazyPagingItems.refresh()) and end-of-list
      behavior.
3. **Implement "True Offline" for Simple Actions with WorkManager:**
    * **Goal:** Allow users to mark messages as read/unread or star/unstar them instantly, with the
      network sync happening reliably in the background.
    * **Task 3.1**: Implement a SyncMessageStateWorker using WorkManager. This worker will take a
      messageId and its new state as input.
    * **Task 3.2**: In DefaultMessageRepository.markMessageRead(), replace the temporary direct API
      call with logic to enqueue a unique OneTimeWorkRequest for the SyncMessageStateWorker. The
      worker should have a network constraint and a retry policy.
    * **Task 3.3**: The worker, upon successful API sync, will update the message's needsSync flag
      back to false in the database.
4. **Implement On-Demand Fetch for Message Bodies:**
    * **Goal:** When a user clicks on a message from the list, fetch its full body from the network
      if it's not already cached.
    * **Task 4.1**: Add MessageBodyEntity to the database and update AppDatabase with a migration (
      MIGRATION\_3\_4).
    * **Task 4.2**: In MessageDetailViewModel, upon loading, check the MessageBodyDao first.
    * **Task 4.3**: If the body is missing, trigger a use case that calls
      mailApiService.getMessageDetails(), saves the result to the MessageBodyDao, and lets the
      reactive UI update automatically.