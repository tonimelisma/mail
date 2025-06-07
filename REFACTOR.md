# Refactoring and Implementation Log for REQ-SYNC-005: Auto-Refresh Message on View

## 1. Objective

Implement `REQ-SYNC-005: Auto-Refresh Message on View`. This feature requires that when a user views a message, if the application is online and the message is considered stale (e.g., older than 5 minutes since its last successful full sync), a silent refresh of its content (body, attachments, and other metadata) should occur. This refresh is orchestrated via the `SyncEngine` by enqueuing a new worker (`SingleMessageSyncWorker`) designed to fetch the full details of a single message.

## 2. Summary of Changes Made (File by File)

The following files were created or modified in an attempt to implement the objective and resolve subsequent build issues:

### Core Data Model & Mappers:
*   **`core-data/src/main/java/net/melisma/core_data/model/Message.kt`**:
    *   Added `remoteId: String?` and `lastSuccessfulSyncTimestamp: Long?` to the `Message` data class.
    *   Updated the `fromApi` factory function to include these new fields.
*   **`data/src/main/java/net/melisma/data/mapper/MessageMappers.kt`**:
    *   Updated mappers (e.g., `toEntity`, `toDomain`) to correctly map the new `remoteId` (as `messageId` in `MessageEntity`) and `lastSuccessfulSyncTimestamp` fields between `MessageEntity` and the `Message` domain model. (Initial implementation had issues here that were iteratively fixed).
*   **`core-data/src/main/java/net/melisma/core_data/model/SyncStatus.kt`**:
    *   Identified as the canonical `SyncStatus` enum. Its definition: `IDLE`, `SYNCED`, `PENDING_UPLOAD`, `PENDING_DOWNLOAD`, `ERROR`.

### API Service & Helpers:
*   **`core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.kt`**:
    *   Added `suspend fun getMessage(messageRemoteId: String): Result<Message>` to the interface for fetching a single complete message.
    *   Corrected the signature of `suspend fun syncMessagesForFolder` to use `folderRemoteId: String` instead of `folderId: String` after a prolonged debugging session.
*   **`backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`**:
    *   Implemented the `getMessage(messageRemoteId: String)` function.
    *   Added helper `parseFullGmailMessage` to map the full Gmail API response to the domain `Message`, populating `remoteId` and `lastSuccessfulSyncTimestamp`. This involved several sub-helper functions for parsing dates, addresses, and message parts, which went through multiple iterations to fix build errors.
    *   Corrected the `syncMessagesForFolder` method signature to use `maxResults: Int?` (was `maxResultsFromInterface`) and to align with the `folderRemoteId` parameter name from the interface.
*   **`backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`**:
    *   Implemented the `getMessage(messageRemoteId: String)` function.
    *   Adapted `mapKtorGraphMessageToDomainMessage` to populate `remoteId` and `lastSuccessfulSyncTimestamp`.
    *   Corrected the `syncMessagesForFolder` method signature to use `maxResults: Int?` (was `maxResultsFromInterface`) and to align with the `folderRemoteId` parameter name from the interface.
    *   Corrected a call from `credentialStore.getUserId()` to `credentialStore.getActiveAccountId()` in `getMessage`.

### Database (DAO, Entities, Converters, Database Class):
*   **`core-db/src/main/java/net/melisma/core_db/entity/SyncStatus.kt`**:
    *   Initially created in this path with states like `NOT_SYNCED`, `SYNC_IN_PROGRESS`, etc.
    *   This was later identified as redundant and conflicting with `core_data.model.SyncStatus` and was **deleted**.
*   **`core-db/src/main/java/net/melisma/core_db/converter/SyncStatusConverter.kt`**:
    *   Initially created to convert the `core_db.entity.SyncStatus`.
    *   Updated to import and convert `net.melisma.core_data.model.SyncStatus` after the enum was unified.
*   **`core-db/src/main/java/net/melisma/core_db/AppDatabase.kt`**:
    *   Investigated for custom transaction utilities (none found).
    *   The `@TypeConverters` annotation was updated to ensure it correctly referenced the (now unified) `SyncStatusConverter` for `net.melisma.core_data.model.SyncStatus`. An inline converter for a different `SyncStatus` was removed.
*   **`core-db/src/main/java/net/melisma/core_db/entity/MessageEntity.kt`**:
    *   Updated to import `net.melisma.core_data.model.SyncStatus`.
    *   The default value for `syncStatus` field was changed to `SyncStatus.IDLE` (from the unified enum).
*   **`core-db/src/main/java/net/melisma/core_db/entity/AttachmentEntity.kt`**:
    *   Updated to import `net.melisma.core_data.model.SyncStatus`.
    *   The default value for `syncStatus` field was changed to `SyncStatus.IDLE` (from the unified enum).
*   **`core-db/src/main/java/net/melisma/core_db/dao/MessageDao.kt`**:
    *   Reviewed for update methods. `insertOrUpdateMessages(List<MessageEntity>)`, `setSyncStatus()`, and `updateLastSyncError()` were identified as key methods for `SingleMessageSyncWorker`.
    *   (No new methods were explicitly added as per the summary, but its existing methods were planned for use by the new worker).
*   **`core-db/src/main/java/net/melisma/core_db/dao/MessageBodyDao.kt`**:
    *   Updated to import `net.melisma.core_data.model.SyncStatus` for its `updateBodyContentAndSyncState` method signature.
*   **`core-db/src/main/java/net/melisma/core_db/dao/AttachmentDao.kt`**:
    *   Reviewed. `deleteAttachmentsForMessage(messageLocalId)` and `insertAttachments(List<AttachmentEntity>)` were identified as key methods for `SingleMessageSyncWorker`.

### Sync Engine & Workers:
*   **`data/src/main/java/net/melisma/data/sync/workers/SingleMessageSyncWorker.kt`**:
    *   Created this new `CoroutineWorker`.
    *   Takes `accountId`, `messageLocalId`, `messageRemoteId` as input.
    *   Fetches message via `apiService.getMessage()`.
    *   Updates local `MessageEntity`, `MessageBodyEntity`, and `AttachmentEntity` list in a Room transaction block (`androidx.room.withTransaction`).
    *   Extensively refactored to:
        *   Use correct DAO methods for updating entities (`insertOrUpdateMessages`, `setSyncStatus`, `updateLastSyncError` for `MessageEntity`; `updateBodyContentAndSyncState` for `MessageBodyEntity`; `deleteAttachmentsForMessage` and `insertAttachments` for `AttachmentEntity`).
        *   Correctly parse timestamps.
        *   Handle `ApiServiceException` and other exceptions.
        *   Use the unified `net.melisma.core_data.model.SyncStatus` (e.g., using `SyncStatus.ERROR` for failures, `SyncStatus.SYNCED` for success, `SyncStatus.PENDING_DOWNLOAD` for new attachments).
*   **`data/src/main/java/net/melisma/data/sync/SyncEngine.kt`**:
    *   Added `refreshMessage(accountId: String, messageLocalId: String, messageRemoteId: String?)`.
    *   This method enqueues `SingleMessageSyncWorker` with `ExistingWorkPolicy.REPLACE`, using `SingleMessageSyncWorker.buildWorkData()` for input.
*   **`data/src/main/java/net/melisma/data/sync/workers/FolderContentSyncWorker.kt`**:
    *   Corrected the call to `mailService.syncMessagesForFolder` in the DELTA SYNC path to use `folderRemoteId` as the parameter name instead of `folderId`, aligning with the `MailApiService` interface definition. This resolved a persistent build error.

### ViewModels:
*   **`mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt`**:
    *   Injected `SyncEngine`.
    *   Modified `evaluateAndTriggerDownloads` for immediate body download if online and body is missing.
    *   Added logic to call `syncEngine.refreshMessage` if the message is stale (older than 5 minutes) and the user is online.
    *   Modified `observeWorkStatus` to reload from source of truth upon successful downloads.
*   **`mail/src/main/java/net/melisma/mail/ui/threaddetail/ThreadDetailViewModel.kt`**:
    *   Injected `SyncEngine`.
    *   Adapted download logic for immediate body download if online and missing.
    *   Added `triggerAutoRefreshIfNeeded` to call `syncEngine.refreshMessage` for stale messages in a thread.
    *   Modified `observeWorkStatus` to rely on main Flow for data refresh.
    *   Changed `enqueueMessageBodyDownload` to use `ExistingWorkPolicy.REPLACE`.

## 3. Current Unresolved Build Issues

As of the last build attempt, the following errors persist, all within `data/src/main/java/net/melisma/data/sync/workers/SingleMessageSyncWorker.kt`:

*   **`Unresolved reference 'withTransaction'`** (at line 100: `androidx.room.withTransaction(appDatabase)`)
    *   This occurs despite:
        *   The presence of `import androidx.room.withTransaction` in the file.
        *   The `androidx.room:room-ktx:2.7.1` dependency being correctly specified in `data/build.gradle.kts`.
        *   `appDatabase` being an instance of `AppDatabase` which extends `RoomDatabase`.
        *   The call using the fully qualified name.
*   **`Suspension functions can only be called within coroutine body`** (for DAO calls at lines 128, 135, 148, 168).
    *   These errors are for DAO methods called inside the lambda block passed to `androidx.room.withTransaction`.
    *   These are likely a direct consequence of `withTransaction` not being resolved correctly, as its lambda block is supposed to provide a `suspend CoroutineScope`.

Errors previously present in `FolderContentSyncWorker.kt` related to `syncMessagesForFolder` parameter names have been **resolved**.

The unification of `SyncStatus` to use `net.melisma.core_data.model.SyncStatus` has resolved numerous type mismatch errors across the `:data` module.

## 4. Strategy for `withTransaction` Issue

The "Unresolved reference 'withTransaction'" is the primary blocker. Given that all direct code inspections (import, dependency, call syntax, receiver type) appear correct, this points to a more subtle issue:

*   **Build Environment/Cache:** Although a `./gradlew clean build` was attempted (and interrupted), a more thorough cache clearing might be needed if the issue is a deeply corrupted Gradle cache on the build machine.
*   **Library Version Incompatibility:** A subtle incompatibility between Room `2.7.1`, Kotlin `2.1.10`, Coroutines `1.9.0`, or KSP `2.1.10-1.0.29` that isn't immediately obvious. This would typically require checking release notes or trying to reproduce in a minimal project.
*   **Compiler/KSP Interaction:** An unknown interaction where KSP (likely Hilt's processor) might be interfering with the Kotlin compiler's ability to correctly resolve this specific extension function in this file.

At this point, without deeper Gradle build analysis tools or the ability to perform more invasive cache clearing, diagnosing the root cause of the `withTransaction` resolution failure is challenging with the current toolset if the code itself is indeed correct. 