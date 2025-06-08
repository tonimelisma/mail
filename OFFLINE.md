# OFFLINE.md: Proposed Enhancements for Offline Functionality

## Introduction

This document outlines the next increment of work focused on solidifying and enhancing the offline capabilities of the Melisma Mail application. The primary goals are to achieve full feature parity for Microsoft account offline actions (especially concerning attachments), verify and complete existing features like on-demand message refresh, and conduct targeted testing of recent major changes to ensure overall system robustness.

This work is intended for an engineering intern, with guidance from senior developers. All code changes should be accompanied by relevant unit and integration tests.

## Section 1: Achieve Full Attachment Parity for Microsoft Graph (Drafts & Send)

**Status: COMPLETED**

**Objective:**
Ensure users can seamlessly create, save, and send drafts with attachments, and compose and send new messages with attachments using a Microsoft account, with full offline support. This involves making sure attachments are correctly uploaded to Microsoft Graph and associated with the message/draft.

**Summary of Implementation:**
The necessary changes were implemented across `GraphApiHelper.kt`, `DefaultMessageRepository.kt`, `ActionUploadWorker.kt`, and related data models (`Attachment.kt`, `AttachmentEntity.kt`). Key aspects included:
*   Implementing `createUploadSessionInternal` and `uploadAttachmentInChunks` in `GraphApiHelper.kt`.
*   Implementing `createDraftMessage` in `GraphApiHelper.kt`, handling small/large attachments, and fetching the final draft state including attachments.
*   Implementing `updateDraftMessage` in `GraphApiHelper.kt`, including fetching existing server attachments, identifying attachments to add/remove, performing deletions, patching core content, adding new attachments, and fetching the final draft state.
*   Updating `DefaultMessageRepository.kt` to correctly save/update local `AttachmentEntity` records, linking them to the draft and storing `localUri` and `accountId`.
*   Ensuring `ActionUploadWorker.kt` correctly processes `ACTION_CREATE_DRAFT` and `ACTION_UPDATE_DRAFT`, and reconciles local `AttachmentEntity` records with server attachment IDs by populating `remoteAttachmentId` and `accountId`.
*   Updating the `Attachment` domain model and `AttachmentEntity` database entity to include `accountId` and `remoteId`/`remoteAttachmentId`.
*   Updating `GmailApiHelper.kt` to align with the updated `Attachment` domain model.

All related compilation errors were resolved, and the build is currently successful.

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

**Status: COMPLETED**

**Objective:**
Ensure that when a user views a message, its content is silently and automatically refreshed from the server if it's considered "stale" (e.g., older than 5 minutes, configurable) and the device is online. This provides the user with the most up-to-date message content without manual intervention.

**Summary of Implementation:**
*   The core logic in `MessageDetailViewModel.kt` to trigger a refresh for a stale message by calling `syncEngine.refreshMessage()` was confirmed to be in place.
*   `SyncEngine.kt` correctly enqueues `SingleMessageSyncWorker.kt` with the necessary `accountId`, `localMessageId`, and `remoteMessageId`.
*   `SingleMessageSyncWorker.kt` was updated to correctly populate `accountId` and `remoteAttachmentId` in `AttachmentEntity` when saving attachments for the refreshed message, aligning it with the updated data models.

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

**Status: COMPLETED**

**Summary of Verification:**
A thorough code review and analysis (Steps 2-5 of the operational plan) confirmed that the existing implementations for outbox functionality and thread-level actions align with the objectives of this section:
*   **Outbox Functionality (3.1 & 3.4):** `DefaultMessageRepository.sendMessage()` correctly creates a local `MessageEntity` with `isOutbox = true` and queues an `ACTION_SEND_MESSAGE`. The `ActionUploadWorker` processes this action, calling the appropriate `MailApiService.sendMessage()`, and upon successful send, correctly deletes the local `isOutbox = true` message from the database using `messageDao.deleteMessageById()`. This ensures the local outbox is cleared and the sent message is not duplicated.
*   **Microsoft Graph Thread-Level Actions (3.2):** `GraphApiHelper.kt` methods (`markThreadRead`, `deleteThread`, `moveThread`) correctly use an iterative approach (listing messages in a conversation, then acting on individual messages). This is consistent with Microsoft Graph API capabilities, which do not offer single API calls for these operations on an entire conversation via `conversationId`.
*   **Gmail Thread-Level Actions (Comparison for 3.2):** `GmailApiHelper.kt` employs an iterative approach for `markThreadRead` but uses direct single API calls for `deleteThread` and `moveThread`, reflecting Gmail API's direct support for these.
*   **`ActionUploadWorker.kt` Usage of Direct Thread Methods (3.3):** The `ActionUploadWorker` correctly invokes the respective API helper methods (`GraphApiHelper` or `GmailApiHelper`) for processing queued thread actions (`ACTION_MARK_THREAD_READ`, `ACTION_DELETE_THREAD`, `ACTION_MOVE_THREAD`), utilizing the direct or iterative approaches as implemented in the helpers.

No code changes were required as the existing system behavior met the specified requirements for this section.

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

## Section 4: Microsoft Graph Attachments - Handoff (Completed)

**Status: COMPLETED**

**Objective:** Complete the implementation for adding attachments to new and existing drafts for Microsoft accounts, and ensure these attachments are correctly uploaded when the message/draft is sent or saved.

**Summary of Resolution:**
This section's objectives were addressed and completed as part of the work detailed in **Section 1: Achieve Full Attachment Parity for Microsoft Graph (Drafts & Send)**. All original tasks related to `GraphApiHelper.kt` DTOs, method implementations for `createDraftMessage`, `updateDraftMessage`, `sendMessage`, helper methods for upload sessions, `DefaultMessageRepository.kt` updates for local persistence, and resolution of related compilation errors have been successfully carried out. The system now supports robust Microsoft Graph attachment handling for drafts and sending messages.

**Current State & Context:**
This section's objectives were addressed and completed as part of the work detailed in **Section 1: Achieve Full Attachment Parity for Microsoft Graph (Drafts & Send)**. All original tasks related to `GraphApiHelper.kt` DTOs, method implementations for `createDraftMessage`, `updateDraftMessage`, `sendMessage`, helper methods for upload sessions, `DefaultMessageRepository.kt` updates for local persistence, and resolution of related compilation errors have been successfully carried out. The system now supports robust Microsoft Graph attachment handling for drafts and sending messages.

**Tasks:**

1.  **`GraphApiHelper.kt` - Initial DTOs and Method Stubs:**
    *   Created Data Transfer Objects (DTOs) required for Microsoft Graph attachment requests: `GraphAttachmentRequest`, `GraphMessageRequest`, `KtorGraphItemBodyRequest`, `KtorGraphRecipientRequest`, `KtorGraphEmailAddressRequest`, `GraphAttachmentItem`, `GraphAttachmentUploadSessionRequest`, and `GraphAttachmentUploadSessionResponse`.
    *   Reviewed and confirmed that `sendMessage` had the core logic for distinguishing small/large attachments and initiating upload sessions.
    *   Attempted to implement `createDraftMessage` and `updateDraftMessage` to handle attachments, including extracting helper methods `createUploadSessionInternal` and `uploadAttachmentInChunks` from the `sendMessage` logic. These methods were intended to centralize attachment upload session creation and chunked uploading.

2.  **`DefaultMessageRepository.kt` - Local Draft Attachment Persistence:**
    *   Modified `createDraftMessage` to iterate through `draftDetails.attachments`, create corresponding `AttachmentEntity` objects (linking them to the draft's `MessageEntity.id` and storing `localUri` in `localFilePath`), and save these using `attachmentDao.insertAttachments()`.
    *   Modified `updateDraftMessage` to first delete all existing `AttachmentEntity` records for the draft message ID using `attachmentDao.deleteAttachmentsForMessage()`, then create and save new `AttachmentEntity` records based on the current `draftDetails.attachments`.

3.  **Build & Compilation Error Iteration:**
    *   Encountered several build failures primarily within `GraphApiHelper.kt`.
    *   **Resolved:** Missing `@ApplicationContext` import (`dagger.hilt.android.qualifiers.ApplicationContext`).
    *   **Attempted Fixes (Unsuccessful/Partially Successful):**
        *   Added a `jsonParser` instance (`kotlinx.serialization.json.Json`).
        *   Removed some duplicate method definitions (e.g., for `getMessageDetails`).
        *   The re-implementation of `createDraftMessage`, `updateDraftMessage`, and the extracted helpers (`createUploadSessionInternal`, `uploadAttachmentInChunks`) was a large change aimed at resolving "unresolved reference" errors. However, this introduced new issues or did not fully resolve existing ones.

**Files Changed During This Session:**

*   `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`:
    *   Added DTOs for attachments and message requests.
    *   Added `dagger.hilt.android.qualifiers.ApplicationContext` import.
    *   Added a `jsonParser` field.
    *   Attempted to implement `createDraftMessage`, `updateDraftMessage` and extract helper methods `createUploadSessionInternal`, `uploadAttachmentInChunks`. *This file is currently in a state with compilation errors and the last major edit was reverted by the user.* The intern should start by carefully reviewing the last version of this file and the compilation errors from the build logs.
*   `data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt`:
    *   Updated `createDraftMessage` to save `AttachmentEntity` records for new drafts.
    *   Updated `updateDraftMessage` to delete old and save new `AttachmentEntity` records for existing drafts.

**Encountered Technical Debt & Unfinished Work:**

1.  **`GraphApiHelper.kt` Compilation Errors:** This is the most pressing issue.
    *   **Current Errors (from last build log):**
        *   `'return' is prohibited here`: Likely within a `forEach` loop in `createDraftMessage` or `updateDraftMessage` (around line 295 in the version where methods were re-implemented). The `return@forEach` might be needed, or the lambda structure reviewed.
        *   `Conflicting overloads: suspend fun getMessagesForThread(...)`: Duplicate definitions of `getMessagesForThread` still exist (around lines 511 and 872 in the version where methods were re-implemented). These need to be consolidated to a single, correct implementation matching the `MailApiService` interface.
    *   **Previous Errors (Potentially Resurfacing or Related):** The previous build logs before the large re-implementation also showed issues like unresolved references to `jsonParser` (which was added but might have been lost in reverts/failed edits), `createUploadSessionInternal`, `uploadAttachmentInChunks`, and ambiguous `Uri` imports. The intern must carefully ensure all necessary helper methods are correctly defined and a single `jsonParser` is available and used.
    *   **Action for Intern:**
        1.  **Obtain the latest version of `GraphApiHelper.kt` from version control before the large, reverted change that introduced `createUploadSessionInternal`, etc., as separate methods.** This version was likely more stable despite other pending work.
        2.  **Incrementally re-introduce the `createDraftMessage` and `updateDraftMessage` methods from the `MailApiService` interface if they are missing.** Ensure their signatures match the interface.
        3.  **Implement the logic for `createDraftMessage`:** Referencing the plan to handle small attachments inline and large attachments via upload sessions (which are part of `sendMessage` and can be refactored carefully).
        4.  **Implement the logic for `updateDraftMessage`:** Similar to `createDraftMessage`, ensuring it handles both small and large attachments. **Crucially, this method needs to be enhanced to handle *removal* of attachments.** The current PATCH approach only replaces the list of small attachments and adds new large ones; it doesn't delete existing attachments on the server if they are removed from the local draft. This requires:
            *   Fetching the current list of attachments for the draft from the server.
            *   Comparing this list with the attachments in the `MessageDraft` passed to the method.
            *   Issuing `DELETE` requests for any server attachments not present in the local draft's attachment list (`DELETE /me/messages/{messageId}/attachments/{attachmentId}`).
            *   Then, adding any new attachments (small inline via PATCH, large via upload session).
        5.  **Refactor Attachment Helpers (`createUploadSessionInternal`, `uploadAttachmentInChunks`):** Once `sendMessage`, `createDraftMessage`, and `updateDraftMessage` are stable, carefully extract these helper methods. Ensure they are correctly defined and used by all three parent methods to avoid code duplication and ensure consistency.
        6.  **Resolve all compilation errors systematically.** Build frequently.

2.  **`GraphApiHelper.kt` - Attachment Removal in `updateDraftMessage`:** As noted above, the current logic for `updateDraftMessage` does *not* handle the removal of attachments that might exist on the server draft but have been removed locally by the user. This is a significant gap for true draft synchronization.
    *   **Action for Intern:** Implement the logic described in point 1.4 above.

3.  **Verification of `ActionUploadWorker.kt` with `GraphApiHelper.kt` Changes:** Once `GraphApiHelper.kt` is stable and correctly handles draft creation/updates with attachments:
    *   **Action for Intern:** Thoroughly test the end-to-end flow: creating a draft with attachments (small and large) offline, updating it offline (adding/removing attachments), and then going online to ensure `ActionUploadWorker` correctly calls `GraphApiHelper.kt` methods (`createDraftMessage`, `updateDraftMessage`) and that attachments are correctly reflected on the server.
    *   Pay close attention to how `remoteId` is handled for newly created drafts that are then updated offline before the initial creation is synced.

**Next Steps for the Intern:**

1.  **Prioritize fixing compilation errors in `GraphApiHelper.kt`**. This is the blocker for any further progress on Microsoft attachments.
    *   Start by cleaning up duplicate methods and ensuring all interface methods from `MailApiService` are correctly stubbed or implemented.
    *   Address the `'return' is prohibited here` error carefully.
    *   Ensure a single, correctly configured `jsonParser` is available and used.
2.  **Implement `createDraftMessage` in `GraphApiHelper.kt`** according to the plan (small attachments inline, large via upload sessions, fetch final message).
3.  **Implement `updateDraftMessage` in `GraphApiHelper.kt`**, paying special attention to the **attachment removal logic** (fetch server attachments, compare, delete, then add/update).
4.  **Refactor `createUploadSessionInternal` and `uploadAttachmentInChunks`** into robust helper methods used by `sendMessage`, `createDraftMessage`, and `updateDraftMessage`.
5.  **Thoroughly test** the draft and send functionalities for Microsoft accounts with various attachment scenarios (no attachments, small only, large only, mixed, adding/removing between saves).
6.  Proceed with **Section 2 and 3** of this document once Microsoft attachment functionality is stable and verified.

**Final Sanity Check for Intern:** Before committing any changes to `GraphApiHelper.kt`, ensure `./gradlew build` runs successfully. Address all Kotlin compiler errors and warnings. Writing unit tests for new logic in `GraphApiHelper.kt` will be crucial.

By addressing these areas, the intern will significantly contribute to the stability and feature completeness of Melisma Mail's offline experience. 