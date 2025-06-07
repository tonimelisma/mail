# OFFLINE.md: Proposed Enhancements for Offline Functionality

## Introduction

This document outlines the next increment of work focused on solidifying and enhancing the offline capabilities of the Melisma Mail application. The primary goals are to achieve full feature parity for Microsoft account offline actions (especially concerning attachments), verify and complete existing features like on-demand message refresh, and conduct targeted testing of recent major changes to ensure overall system robustness.

This work is intended for an engineering intern, with guidance from senior developers. All code changes should be accompanied by relevant unit and integration tests.

## Section 1: Achieve Full Attachment Parity for Microsoft Graph (Drafts & Send)

**Objective:**
Ensure users can seamlessly create, save, and send drafts with attachments, and compose and send new messages with attachments using a Microsoft account, with full offline support. This involves making sure attachments are correctly uploaded to Microsoft Graph and associated with the message/draft.

**Current State & Context:**
Currently, attachment handling for Microsoft Graph (`GraphApiHelper.kt`) might be incomplete or use placeholder logic for creating/updating drafts and sending messages. `DefaultMessageRepository.kt` orchestrates draft saving and message sending, and it needs to correctly interface with `GraphApiHelper.kt` to manage attachments. Gmail's attachment handling (`GmailApiHelper.buildRfc2822Message`) serves as a good reference for robust MIME message construction.

**Tasks:**

1.  **Review `GraphApiHelper.kt` for Attachment Capabilities:**
    *   **Action:** Thoroughly examine the existing methods in `GraphApiHelper.kt` related to:
        *   `createDraft(...)`
        *   `updateDraft(...)`
        *   `sendMessage(...)`
    *   **Focus:** Identify how attachments are currently processed (if at all). Determine if the methods accept attachment data and if they construct API requests compatible with Graph API's attachment upload mechanisms (e.g., attaching directly for small files, creating upload sessions for large files).
    *   **Files to read for context:**
        *   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`
        *   Microsoft Graph API documentation:
            *   [Add attachments to a message](https://learn.microsoft.com/en-us/graph/api/message-post-attachments)
            *   [Add attachments to a draft message](https://learn.microsoft.com/en-us/graph/api/draftmessage-post-attachments)
            *   [Create an upload session for large attachments](https://learn.microsoft.com/en-us/graph/api/attachment-createuploadsession)

2.  **Implement/Enhance Attachment Handling in `GraphApiHelper.kt`:**
    *   **Action:** Modify the identified methods (`createDraft`, `updateDraft`, `sendMessage`) to fully support attachments.
    *   **Implementation Details:**
        *   **Input:** Methods should accept a list of attachment objects/data (e.g., containing file URIs/paths, MIME types, names).
        *   **Content Reading:** Implement logic to read attachment content from their URIs/paths.
        *   **Request Construction:**
            *   For messages with attachments, the Graph API typically expects a `attachments` array in the message resource.
            *   Each attachment object within the array needs `@odata.type`, `name`, `contentType`, and `contentBytes` (for small attachments) or an upload session mechanism for large attachments.
            *   Refer to Ktor documentation for multipart request construction if needed, although Graph often uses JSON bodies with base64 encoded content for smaller files.
        *   **Large File Handling (>3MB for Graph):** Implement the upload session mechanism:
            1.  Create an upload session using `message:createUploadSession`.
            2.  Upload file content in chunks to the `uploadUrl` obtained from the session.
            3.  The final attachment object in the message payload will then reference the uploaded attachment.
        *   **Error Handling:** Implement robust error handling for file reading, API requests, and attachment uploads. Map Graph API errors to `MelismaError` types.
    *   **Reference:**
        *   `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt` (see `buildRfc2822Message` for complex MIME construction, though Graph's approach is different, the principle of handling various parts is relevant).

3.  **Update `DefaultMessageRepository.kt` Integration:**
    *   **Action:** Ensure `saveDraft` and `sendMessage` methods in `DefaultMessageRepository.kt` correctly prepare and pass attachment information to the updated `GraphApiHelper.kt` methods.
    *   **Details:**
        *   When saving a draft or sending a message, collect all attachment details (local URIs, filenames, MIME types).
        *   Pass this data in a structured way to the `GraphApiHelper.kt` methods.
        *   For offline drafts with attachments, ensure local paths/URIs to attachments are persistently stored (e.g., in `DraftAttachmentEntity` if such an entity exists or is created, or as part of the `PendingActionEntity` payload) so they can be accessed when the `ActionUploadWorker` processes the action online.
    *   **Files to read for context:**
        *   `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
        *   `core-db/src/main/java/net/melisma/core_db/entity/PendingActionEntity.kt` (to understand payload storage)

**Intern Reading List for Section 1:**
*   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`
*   `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
*   `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt` (for general attachment concepts, not direct Graph implementation)
*   Microsoft Graph API documentation (links provided above).
*   Ktor client documentation (for making HTTP requests).

---

## Section 2: Complete and Verify REQ-SYNC-005 (Auto-Refresh Message on View)

**Objective:**
Ensure that when a user views a message, its content is silently and automatically refreshed from the server if it's considered "stale" (e.g., older than 5 minutes, configurable) and the device is online. This provides the user with the most up-to-date message content without manual intervention.

**Current State & Context:**
Initial work on this feature was previously undertaken. This task focuses on verifying its completeness, correctness, and integration across relevant components. The core idea is that the ViewModel responsible for displaying message details checks for staleness and, if needed, triggers a background sync for that specific message via `SyncEngine`.

**Tasks:**

1.  **Review `MessageDetailViewModel.kt` (or equivalent):**
    *   **Action:** Examine the logic for staleness detection and refresh triggering.
    *   **Focus:**
        *   **Staleness Check:** How is `isStale()` (or similar) implemented? It should compare the message's local load/sync timestamp against the current time and a defined threshold.
        *   **Connectivity Check:** Verify that an online check (`ConnectivityRepository.isConnected`) is performed before attempting a refresh.
        *   **Triggering Sync:** Confirm that `SyncEngine.enqueueOnDemandMessageDownload(accountId, messageId)` (or equivalent) is called correctly.
        *   **UI Feedback:** While the refresh is "silent," consider if any subtle UI cues are needed or if the UI smoothly updates upon data arrival.
    *   **Files to read for context:**
        *   `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt` (or the actual ViewModel used for message details)
        *   `core-data/src/main/java/net/melisma/core_data/repository/ConnectivityRepository.kt`
        *   `BACKLOG.MD` (for REQ-SYNC-005 definition)

2.  **Review `SyncEngine.kt` for On-Demand Download:**
    *   **Action:** Verify the implementation of the on-demand message download mechanism.
    *   **Focus:**
        *   **WorkManager Task:** How is the specific message download enqueued and prioritized? (e.g., using a unique `WorkRequest` for `OnDemandMessageDownloadWorker`).
        *   **Data Fetching:** The worker should call the appropriate `MailApiService` method (e.g., `downloadMessageContent`) which then delegates to `GmailApiHelper.getMessage()` or `GraphApiHelper.getMessageContent()`. Ensure this fetches the *full and latest* message content.
        *   **Database Update:** The fetched message data (body, attachments, latest headers) must be correctly upserted into the local database (e.g., `MessageEntity`, `MessageBodyEntity`, `AttachmentEntity`).
        *   **Error Handling:** How are failures in fetching or updating handled?
    *   **Files to read for context:**
        *   `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
        *   Potentially a new `OnDemandMessageDownloadWorker.kt` if it was created.
        *   `data/src/main/java/net/melisma/data/datasource/MailApiService.kt`

3.  **Verify API Helper Methods for Full Content Fetch:**
    *   **Action:** Ensure the `getMessage()` (Gmail) and `getMessageContent()` (Graph) methods in the respective API helpers are capable of fetching the *absolute latest* version of a message, bypassing any local/delta-sync caches if necessary for this specific on-demand task.
    *   **Focus:** These methods should request the full message payload from the server.
    *   **Files to read for context:**
        *   `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
        *   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`

4.  **Database and UI Observation:**
    *   **Action:** Confirm the UI (observing ViewModel LiveData/Flows sourced from the database) updates automatically and correctly when the refreshed message data is written to the database.
    *   **Files to read for context:**
        *   Relevant `MessageDao.kt` methods.
        *   `MessageDetailViewModel.kt`'s data observation logic.

**Intern Reading List for Section 2:**
*   `mail/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt` (or equivalent)
*   `data/src/main/java/net/melisma/data/sync/SyncEngine.kt`
*   `data/src/main/java/net/melisma/data/datasource/MailApiService.kt`
*   `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
*   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`
*   `core-data/src/main/java/net/melisma/core_data/repository/SyncSettingsRepository.kt` (if staleness threshold is stored here)
*   `BACKLOG.MD`

---

## Section 3: Targeted Testing and Robustness for Recent Major Changes

**Objective:**
Systematically review and test recently implemented core offline features: the outbox mechanism, thread-level actions for Microsoft Graph, the use of direct thread actions in `ActionUploadWorker`, and cache eviction for sent outbox messages. This is crucial for ensuring stability and correct behavior under various conditions.

**Current State & Context:**
Significant changes were made including adding `isOutbox` to `MessageEntity`, implementing Microsoft Graph thread actions, and refactoring `ActionUploadWorker` to use direct thread methods. `ARCHITECTURE.MD` also mentions cache eviction for outbox messages.

**Tasks:**

1.  **Outbox Functionality Deep Dive:**
    *   **Action:** Review the code paths and test the behavior of messages being sent.
    *   **Focus:**
        *   **`DefaultMessageRepository.sendMessage()`:** Verify it correctly creates a `MessageEntity` with `isOutbox = true` and enqueues an `ACTION_SEND_MESSAGE` with `SyncEngine`.
        *   **`ActionUploadWorker` Processing:**
            *   How does it handle `ACTION_SEND_MESSAGE`?
            *   Upon successful send via `MailApiService.sendMessage()`, what updates are made to the local `MessageEntity`? (e.g., `isOutbox` set to `false`, `remoteId` updated, or message replaced by server version on next sync).
            *   What happens on send failure (temporary network issue, permanent API error)? Does the action remain queued for retry? Is the message still `isOutbox`?
        *   **Database State:** Ensure consistency. The message should not be "lost" or duplicated after sending.
    *   **Files to read for context:**
        *   `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
        *   `core-db/src/main/java/net/melisma/core_db/entity/MessageEntity.kt`
        *   `data/src/main/java/net/melisma/data/sync/ActionUploadWorker.kt`
        *   `data/src/main/java/net/melisma/data/datasource/MailApiService.kt` (and its implementations)
        *   `ARCHITECTURE.MD` (section on Outbox)

2.  **Microsoft Graph Thread-Level Actions Implementation Review:**
    *   **Action:** Verify the implementation of `markThreadRead`, `deleteThread`, and `moveThread` in `GraphApiHelper.kt`.
    *   **Focus:**
        *   **Correct API Usage:** Are the correct Graph API endpoints and request bodies used for these thread-level operations?
        *   **Error Handling:** How are API errors handled and mapped?
        *   **Interaction with `ActionUploadWorker`:** Confirm `ActionUploadWorker` correctly calls these methods via `MailApiService` when processing queued thread actions for Microsoft accounts.
    *   **Files to read for context:**
        *   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`
        *   `data/src/main/java/net/melisma/data/sync/ActionUploadWorker.kt`
        *   Microsoft Graph API documentation for thread operations.

3.  **`ActionUploadWorker.kt` Usage of Direct Thread Methods:**
    *   **Action:** Confirm that `ActionUploadWorker` now uses the direct thread-level methods (e.g., `MailApiService.deleteThread()`) instead of iterating through messages within a thread for such actions.
    *   **Focus:** This change was intended for efficiency and correctness. Verify the logic in `ActionUploadWorker` that dispatches `PendingActionEntity` of type `ACTION_DELETE_THREAD`, `ACTION_MOVE_THREAD`, etc., to these direct service calls.
    *   **Files to read for context:**
        *   `data/src/main/java/net/melisma/data/sync/ActionUploadWorker.kt`
        *   `data/src/main/java/net/melisma/data/datasource/MailApiService.kt`

4.  **Cache Eviction / State Update for Sent Outbox Messages:**
    *   **Action:** Investigate and confirm the mechanism for handling messages once they are successfully sent from the outbox.
    *   **Focus:**
        *   As per `ARCHITECTURE.MD`: "Cache eviction strategies for messages successfully sent from the outbox."
        *   When `ActionUploadWorker` successfully sends a message (originally `isOutbox = true`), how is its local state updated?
            *   Is `isOutbox` simply flipped to `false`?
            *   Is the local copy deleted, expecting the server's "Sent Items" version to sync down?
            *   Is the local copy updated with `remoteId` and other server-authoritative info?
        *   The goal is to avoid the message remaining as a purely local entity, distinct from the version in the server's "Sent" folder, and to ensure it doesn't show in the "Outbox" UI anymore.
    *   **Files to read for context:**
        *   `data/src/main/java/net/melisma/data/sync/ActionUploadWorker.kt`
        *   `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
        *   `ARCHITECTURE.MD`

**Intern Reading List for Section 3:**
*   `data/src/main/java/net/melisma/data/sync/ActionUploadWorker.kt`
*   `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`
*   `data/src/main/java/net/melisma/data/repository/DefaultThreadRepository.kt`
*   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`
*   `core-db/src/main/java/net/melisma/core_db/entity/MessageEntity.kt`
*   `core-db/src/main/java/net/melisma/core_db/entity/PendingActionEntity.kt`
*   `ARCHITECTURE.MD`

---

## General Robustness Checks (Applicable to all sections)

*   **Comprehensive Error Handling:** Ensure all new and modified code paths, especially those involving API calls (`GraphApiHelper.kt`, `ActionUploadWorker.kt`) and database interactions, have robust error handling. Errors should be caught, logged appropriately, and mapped to user-understandable `MelismaError` types where applicable. Consider retry mechanisms for transient errors.
*   **Idempotency of Actions:** For offline actions processed by `ActionUploadWorker`, strive for idempotency. If an action is attempted multiple times due to retries, it should not result in unintended side effects (e.g., moving a message multiple times).
*   **Database Transactions:** Ensure all related database operations (e.g., updating a message and its related entities, or saving an action and updating UI state optimistically) are performed within database transactions (`AppDatabase.withTransaction { ... }`) to maintain data consistency.
*   **Unit and Integration Tests:** All new functionalities and significant modifications must be covered by:
    *   **Unit Tests:** For individual components like ViewModel logic, mappers, specific functions in API helpers.
    *   **Integration Tests:** For flows like `Repository -> DataSource -> ApiHelper` or `ActionUploadWorker -> ApiService`.

By addressing these areas, the intern will significantly contribute to the stability and feature completeness of Melisma Mail's offline experience. 