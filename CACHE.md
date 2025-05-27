# **Melisma Mail \- Caching & Offline-First Implementation Guide**

Version: 1.3  
Date: May 27, 2025

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
* **Phase 2c: Build System & Paging Compose Fixes (Completed)**
    * **Resolved KSP Hilt errors for `CoroutineDispatcher` in `:data`**:
        * Initially, `DefaultAccountRepository` failed to resolve `CoroutineDispatcher`.
        * Fixed by adding explicit imports for `kotlinx.coroutines.CoroutineDispatcher`,
          `net.melisma.core_data.di.Dispatcher`, and `net.melisma.core_data.di.MailDispatchers` in
          `DefaultAccountRepository.kt`.
    * **Fixed `:data` module Kotlin compilation errors**:
        * Added missing Room (`libs.androidx.room.runtime`, `libs.androidx.room.ktx`) and Paging (
          `libs.androidx.paging.runtime`) dependencies to `data/build.gradle.kts`.
        * Corrected `Message` domain object construction within `MessageRemoteMediator.kt` to align
          with the `Message.kt` domain model, resolving field mismatches.
        * Extensive refactoring of `DefaultAccountRepository.kt`:
            * The `AccountEntity.kt` in `:core-db` was updated to include
              `needsReauthentication: Boolean`. (Note: This implies a new database migration, e.g.,
              `MIGRATION_3_4` if previous was 2->3, is required but was not explicitly created in
              this session log).
            * `AccountMappers.kt` was created in `:data` with `Account.toEntity()` and
              `AccountEntity.toDomainAccount()` to handle conversions.
            * Removed old in-memory state management (`_googleAccounts`, `_accounts`), now relying
              on DAO observation in the `init` block for `overallApplicationAuthState` and active
              account management.
            * Refactored `markAccountForReauthentication` to update
              `AccountEntity.needsReauthentication` via the DAO.
            * Corrected error handling and type mismatches for `GoogleSignInResult`,
              `GoogleSignOutResult` (including removing a non-existent `Loading` state and ensuring
              `when` exhaustiveness), and `MappedErrorDetails` constructor calls.
            * Modified `GoogleAuthManager.signOut` method signature to accept `accountId: String`
              instead of `ManagedGoogleAccount` to simplify calls from `DefaultAccountRepository`.
            * Resolved numerous nullability issues (e.g., using `e.message ?: "Unknown cause"`) and
              argument type mismatches through careful type checking, explicit casting where safe,
              and ensuring correct Hilt flow collection (e.g., `.first()` for
              `Flow<GoogleSignOutResult>`).
        * Fixed `DefaultMessageRepository.kt`:
            * Added `@OptIn(ExperimentalPagingApi::class)` to `getMessagesPager` method.
            * Corrected `PagingData.map` usage by adding an explicit import for
              `androidx.paging.map` and ensuring correct lambda parameter typing.
            * **A critical fix was made to a `Log.e` call in `markMessageRead`'s catch block. The
              arguments were incorrect (passing an `Exception` as the tag). This was changed
              to `Timber.tag(TAG).e(exception, message)`, which resolved a very persistent and
              misleading "Argument type mismatch" error that the compiler was reporting
              in `DefaultAccountRepository.kt` for a different issue.**
    * **Fixed `:app` module Kotlin compilation errors**:
        * Added `androidx.paging.PagingConfig` import to `MainViewModel.kt`.
        * Removed the `messages: List<Message>` field from `MainScreenState` in `MainViewModel.kt`
          as Paging 3 now uses `LazyPagingItems` in the UI. Adjusted `setViewModePreference`
          accordingly.
        * Ensured `threadRepository.setTargetFolderForThreads` in `MainViewModel.kt` is called from
          a `viewModelScope.launch` coroutine context and changed an `applicationContext` argument
          to `null` for the `Activity?` parameter where an activity instance was not suitable.
        * Added a `modifier: Modifier = Modifier` parameter to the `LoadingIndicator` Composable in
          `MainAppScreen.kt` and applied it.
        * Replaced usages of a missing custom `ButtonPrimary` Composable with the standard
          `androidx.compose.material3.Button` in `MessageListContent.kt`.
        * Added missing string resources (`unknown_error`, `action_retry`, `loading_more_messages`,
          `error_loading_more_failed`) to `app/src/main/res/values/strings.xml`.
        * Added the `implementation(libs.androidx.paging.compose)` dependency to
          `app/build.gradle.kts`, which was crucial for resolving many `LazyPagingItems` related
          errors.
    * **Resolved Hilt `DuplicateBindings` errors**:
        * **`@MicrosoftRepo AccountRepository`**: Deleted the redundant
          `BackendMicrosoftBindsModule.kt` file from the `backend-microsoft` module, as
          `MicrosoftRepositoryModule.kt` (created earlier) already provided the same binding.
        * **`kotlinx.coroutines.CoroutineScope`**: Removed the unqualified `CoroutineScope`
          provider (`provideApplicationCoroutineScope`) from `AppProvidesModule.kt` in the `:app`
          module. The existing provider in `DispatchersModule.kt` (in `:core-data`) now serves as
          the sole singleton provider for the application-wide `CoroutineScope` (using the IO
          dispatcher).
    * **Build Status**: The project is now **BUILDING SUCCESSFULLY**.

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

## **V. Session Updates (Current Session Summary)**

This section tracks work completed during the interactive development session.

**Phase 2c: Build System & Paging Compose Fixes (Completed)**

This extensive troubleshooting and refactoring session focused on getting the project from a
non-building state with numerous Hilt and Kotlin compilation errors to a **successfully building
state**.

* **Resolved KSP Hilt errors for `CoroutineDispatcher` in `:data`**:
  * Initially, `DefaultAccountRepository` failed to resolve `CoroutineDispatcher`.
  * Fixed by adding explicit imports for `kotlinx.coroutines.CoroutineDispatcher`,
  `net.melisma.core_data.di.Dispatcher`, and `net.melisma.core_data.di.MailDispatchers` in
  `DefaultAccountRepository.kt`.

* **Fixed `:data` module Kotlin compilation errors**:
  * Added missing Room (`libs.androidx.room.runtime`, `libs.androidx.room.ktx`) and Paging (
  `libs.androidx.paging.runtime`) dependencies to `data/build.gradle.kts`.
  * Corrected `Message` domain object construction within `MessageRemoteMediator.kt` to align with
  the `Message.kt` domain model, resolving field mismatches.
  * Extensive refactoring of `DefaultAccountRepository.kt`:
  * The `AccountEntity.kt` in `:core-db` was updated to include `needsReauthentication: Boolean`. (
  Note: This implies a new database migration, e.g., `MIGRATION_3_4` if previous was 2->3, is
  required but was not explicitly created in this session log).
  *   `AccountMappers.kt` was created in `:data` with `Account.toEntity()` and
  `AccountEntity.toDomainAccount()` to handle conversions.
  * Removed old in-memory state management (`_googleAccounts`, `_accounts`), now relying on DAO
  observation in the `init` block for `overallApplicationAuthState` and active account management.
  * Refactored `markAccountForReauthentication` to update `AccountEntity.needsReauthentication` via
  the DAO.
  * Corrected error handling and type mismatches for `GoogleSignInResult`, `GoogleSignOutResult` (
  including removing a non-existent `Loading` state and ensuring `when` exhaustiveness), and
  `MappedErrorDetails` constructor calls.
  * Modified `GoogleAuthManager.signOut` method signature to accept `accountId: String` instead of
  `ManagedGoogleAccount` to simplify calls from `DefaultAccountRepository`.
  * Resolved numerous nullability issues (e.g., using `e.message ?: "Unknown cause"`) and argument
  type mismatches through careful type checking, explicit casting where safe, and ensuring correct
  Hilt flow collection (e.g., `.first()` for `Flow<GoogleSignOutResult>`).
  * Fixed `DefaultMessageRepository.kt`:
  * Added `@OptIn(ExperimentalPagingApi::class)` to `getMessagesPager` method.
  * Corrected `PagingData.map` usage by adding an explicit import for `androidx.paging.map` and
  ensuring correct lambda parameter typing.
  *   **A critical fix was made to a `Log.e` call in `markMessageRead`'s catch block. The arguments
  were incorrect (passing an `Exception` as the tag). This was changed
  to `Timber.tag(TAG).e(exception, message)`, which resolved a very persistent and misleading "
  Argument type mismatch" error that the compiler was reporting in `DefaultAccountRepository.kt` for
  a different issue.**

* **Fixed `:app` module Kotlin compilation errors**:
  * Added `androidx.paging.PagingConfig` import to `MainViewModel.kt`.
  * Removed the `messages: List<Message>` field from `MainScreenState` in `MainViewModel.kt` as
  Paging 3 now uses `LazyPagingItems` in the UI. Adjusted `setViewModePreference` accordingly.
  * Ensured `threadRepository.setTargetFolderForThreads` in `MainViewModel.kt` is called from a
  `viewModelScope.launch` coroutine context and changed an `applicationContext` argument to `null`
  for the `Activity?` parameter where an activity instance was not suitable.
  * Added a `modifier: Modifier = Modifier` parameter to the `LoadingIndicator` Composable in
  `MainAppScreen.kt` and applied it.
  * Replaced usages of a missing custom `ButtonPrimary` Composable with the standard
  `androidx.compose.material3.Button` in `MessageListContent.kt`.
  * Added missing string resources (`unknown_error`, `action_retry`, `loading_more_messages`,
  `error_loading_more_failed`) to `app/src/main/res/values/strings.xml`.
  * Added the `implementation(libs.androidx.paging.compose)` dependency to `app/build.gradle.kts`,
  which was crucial for resolving many `LazyPagingItems` related errors.

* **Resolved Hilt `DuplicateBindings` errors**:
  *   **`@MicrosoftRepo AccountRepository`**: Deleted the redundant `BackendMicrosoftBindsModule.kt`
  file from the `backend-microsoft` module, as `MicrosoftRepositoryModule.kt` (created earlier)
  already provided the same binding.
  *   **`kotlinx.coroutines.CoroutineScope`**: Removed the unqualified `CoroutineScope` provider (
  `provideApplicationCoroutineScope`) from `AppProvidesModule.kt` in the `:app` module. The existing
  provider in `DispatchersModule.kt` (in `:core-data`) now serves as the sole singleton provider for
  the application-wide `CoroutineScope` (using the IO dispatcher).

* **Build Status**: The project is now **BUILDING SUCCESSFULLY**.

**Potential Issues & Areas for Future Review from this Session:**

* **Database Migration for `AccountEntity`**: The addition of `needsReauthentication` to
  `AccountEntity` requires a new Room database migration. This was not explicitly created or tracked
  during this session. Failure to add this will result in runtime crashes for existing
  installations.
* **`Activity?` Argument in `MainViewModel`**: Passing `null` for the `Activity?` parameter to
  `threadRepository.setTargetFolderForThreads` from `MainViewModel.onFolderSelected` should be
  reviewed. If `setTargetFolderForThreads` genuinely requires an `Activity` for some operations (
  e.g., triggering auth flows), this functionality might be impaired or alternative approaches might
  be needed.
* **Missing `ButtonPrimary` Composable**: The custom `ButtonPrimary` was replaced with a standard
  `Button`. If `ButtonPrimary` had specific styling or functionality, this has been lost and might
  need to be recreated or the standard `Button` styled appropriately.
* **Root Cause of Misleading Compiler Errors**: The session highlighted how a single, misplaced
  error (like the `Log.e` arguments) can cause the compiler to report errors in unrelated files,
  making debugging significantly harder. This underscores the importance of careful, systematic
  error checking.
* **Thorough Paging 3 Testing**: While the Paging 3 setup now compiles, runtime testing of initial
  load, append, refresh, and error states for the message list is essential.

---

## **VI. Session Updates (May 27, 2025)**

This session focused on addressing issues identified in "Phase 2c" and moving the Paging 3
implementation closer to a functional state.

**Key Accomplishments:**

* **Build Stability:**
    * Identified and fixed a build error in `DefaultMessageRepository.kt` by adding the missing
      `import androidx.paging.map`.
    * The project now **builds successfully** again.
* **Database Migration for `AccountEntity.needsReauthentication`:**
    * The `AccountEntity.kt` was confirmed to have the `needsReauthentication: Boolean` field.
    * `AppDatabase.kt` was updated:
        * Database version incremented from 3 to 4.
        * A new migration `MIGRATION_3_4` was added to alter the `accounts` table, adding the
          `needsReauthentication` column (`INTEGER NOT NULL DEFAULT 0`).
        * This resolves a critical issue that would have caused runtime crashes for existing users.
* **Investigated `Activity?` Argument in `MainViewModel`**:
    * Reviewed `MainViewModel.kt`, `ThreadRepository.kt` (interface), and
      `DefaultThreadRepository.kt` (implementation).
    * Confirmed that while `setTargetFolderForThreads` accepts an `Activity?` (intended for auth
      during refresh), the `Activity` parameter is **not currently used** by
      `DefaultThreadRepository` when called from `MainViewModel.selectFolder` (where `null` is
      passed) or even during its internal refresh logic triggered by this call.
    * **Conclusion**: Passing `null` is not causing an issue with the current implementation. This
      item can be removed from "Potential Issues."
* **`MessageRemoteMediator` Refactoring for Paging 3:**
    * Reviewed `MailApiService.kt` and confirmed that the `getMessagesForFolder` method only
      supports a `maxResults` limit and does not provide pagination keys/tokens. This means true
      network-level append via `RemoteMediator` is not possible with the current API definition.
    * Refactored `data/src/main/java/net/melisma/data/paging/MessageRemoteMediator.kt`:
        * The simulated API call was replaced.
        * For `LoadType.REFRESH`, it now calls the actual `mailApiService.getMessagesForFolder()`
          with a defined `REFRESH_PAGE_SIZE` (100).
        * Fetched messages are stored in the database (clearing previous ones for that
          folder/account).
        * `MediatorResult.Success(endOfPaginationReached = true)` is returned after a `REFRESH`, as
          the API doesn't support further pagination via the mediator.
        * `LoadType.APPEND` and `LoadType.PREPEND` now correctly return
          `MediatorResult.Success(endOfPaginationReached = true)` immediately, as network-driven
          append/prepend is not supported by this mediator with the current API. The `PagingSource`
          from the DAO is responsible for serving data from the database.
        * This change makes the `RemoteMediator` primarily responsible for the initial/refresh
          network fetch, aligning with SSoT and API capabilities.

**Build Status**: The project is **BUILDING SUCCESSFULLY**.

## **VII. Revised Known Technical Debt & Shortcuts (As of May 27, 2025)**

This section consolidates all known shortcuts and areas needing improvement.

* **API-Model Discrepancies**: The core_data.model.Message was enhanced to align with the database
  entity, but the backing MailApiService might not provide all the new fields. Mappers currently use
  default values, which could lead to incomplete cached data. *(Unchanged)*
* **Stubbed API Services**: Methods like `getMessageAttachments` in `DefaultMessageRepository` are
  stubbed. *(Unchanged)*
* **Sync Transactionality**: Broader sync processes (API call + DAO ops) are not fully
  transactional. *(Unchanged)*
* **Error Handling in Mappers**: Date parsing in `MessageMappers` defaults to current time on
  failure. *(Unchanged)*
* **Limited Offline Writes**: Full offline outbox pattern for actions like delete, move, send is
  needed. *(Unchanged)*
* **Log Spam**: Numerous debug logs need review. *(Unchanged)*
* **`MailApiService` Pagination Limitation**: The `MailApiService.getMessagesForFolder` method does
  **not** support true pagination (e.g., page tokens/cursors). It only supports a `maxResults`
  limit. This means:
    * `MessageRemoteMediator` cannot implement network-level `APPEND` for infinite scrolling from
      the network. It can only perform a `REFRESH` (e.g., load top N messages).
    * "Infinite scroll" of messages relies entirely on the `PagingSource` from the Room database
      serving already-fetched-and-stored messages.
* **Missing `ButtonPrimary` Composable**: The custom `ButtonPrimary` was replaced with a standard
  `androidx.compose.material3.Button` in `MessageListContent.kt` (for the retry action). If
  `ButtonPrimary` had specific app-wide styling or functionality, this has been lost and might need
  to be recreated or the standard `Button` styled appropriately for UI consistency. This is
  currently a minor UI debt.
* **Simplified RemoteMediator (Partially Addressed)**: `MessageRemoteMediator` no longer uses a
  *simulated* API call. It calls the real `MailApiService`. However, due to API limitations (see "
  MailApiService Pagination Limitation"), it cannot implement full network-backed pagination and has
  a simplified role (REFRESH only from network).

## **VIII. Potential Next Steps & Future Considerations**

1. **Runtime Testing of Paging 3 Message List:**
    * **Goal**: Verify the message list powered by `Pager`, `MessageRemoteMediator` (for refresh),
      and `MessageDao`'s `PagingSource` works correctly at runtime.
    * **Tasks**:
        * Manually test initial message load when selecting a folder.
        * Test pull-to-refresh functionality.
        * Observe behavior with empty folders and folders that return API errors during the refresh.
        * Ensure loading indicators and error states in `MessageListContent.kt` are displayed
          correctly based on `LazyPagingItems.loadState`.
2. **Enhance `MailApiService` for Pagination (Longer Term):**
    * **Goal**: Introduce proper pagination support in `MailApiService` and its implementations if
      backend APIs allow.
    * **Tasks**:
        * Investigate if Gmail/Graph APIs offer cursor/token-based pagination for
          `getMessagesForFolder`.
        * If so, update `MailApiService.getMessagesForFolder` to accept a page token and return a
          `PagedMessageResponse` (or similar) containing messages and the next page token.
        * Update `MessageRemoteMediator` to implement a full `RemoteKeys` strategy for
          `LoadType.APPEND` if the API is enhanced.
3. **Implement "True Offline" for Simple Actions with WorkManager (As per original plan):**
    * Focus on `markMessageRead` using `WorkManager` for background sync.
4. **Implement On-Demand Fetch for Message Bodies (As per original plan):**
    * Add `MessageBodyEntity`, migration, and DAO/ViewModel logic.
5. **Address Other Tech Debt:**
    * Systematically review and fix items in "Revised Known Technical Debt" (API-Model
      discrepancies, mappers, offline writes, log spam).
    * Decide on the `ButtonPrimary` styling.

The old "IV. Next Manageable Increment" and "V. Session Updates (Current Session Summary)"
including "Potential Issues & Areas for Future Review from this Session" should be considered
superseded by these new sections.