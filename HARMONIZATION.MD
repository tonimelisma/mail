# Email Experience Harmonization Plan (Google & Microsoft)

## 1. Outcomes

* **Consistent User Experience:** Users will see a uniform display of email threads and messages,
  regardless of whether the account is Google (Gmail) or Microsoft (Outlook).
* **Accurate Participant Display:** For any list item representing a conversation (in Inbox, other
  folders, or dedicated thread views), all participants will be summarized (e.g., "Alice, Bob & 2
  more") for both Google and Microsoft accounts. Single messages not part of a conversation will
  show only their sender.
* **Simplified Code Maintenance:** A unified data flow and UI rendering logic for threads will
  reduce redundancy and make future modifications easier.
* **Leveraged Existing Strengths:** The plan will build upon the existing robust `MailThread` model
  and the thread assembly logic in `DefaultThreadRepository`.

## 2. Objectives

* **Harmonize Folder View Display:** Ensure that primary folder views (Inbox, Sent, custom folders,
  etc.) display items as `MailThread` objects, utilizing `ThreadListItem.kt` for rendering, to show
  full participant summaries for conversations.
* **Maintain Distinct Message View:** Retain the ability to show individual messages (using
  `MessageListItem.kt`) when viewing the contents of an opened thread or in contexts where a flat
  list of distinct messages is required (e.g., certain search results).
* **Unify Data Fetching for Threaded Lists:** Ensure `DefaultThreadRepository` is the central
  component for fetching and assembling `MailThread` objects for all conversation-aware list views.
* **Abstract Provider Specifics:** Keep differences in API calls (Gmail API vs. Microsoft Graph API)
  encapsulated within their respective `MailApiService` implementations (`GmailApiHelper.kt`,
  `GraphApiHelper.kt`).

## 3. Requirements

* **Data Models:**
    * `core_data.model.Message`: Must consistently populate `threadId` (Gmail `threadId` or Outlook
      `conversationId`), `senderName`, and `senderAddress`.
    * `core_data.model.MailThread`: Must be the primary data object for displaying items in
      conversation-aware lists. Its `participantsSummary` will be the source of truth for sender
      display in such lists.
* **Backend API Services (`MailApiService` implementations):**
    * `getMessagesForFolder(...)`: Must return `List<Message>`, each with a populated `threadId`.
    * `getMessagesForThread(...)`: Must return all messages belonging to the given `threadId`/
      `conversationId` **from across the entire mailbox, not just a specific folder, to ensure true
      cross-folder threading.**
* **Repository (`DefaultThreadRepository`):**
    * Must correctly identify unique threads within a folder.
    * Must fetch all messages for each identified thread.
    * Must use the generic `assembleMailThread(...)` function to create `MailThread` objects,
      including `participantsSummary`.
    * Must expose `StateFlow<ThreadDataState>` where `ThreadDataState.Success` contains a
      `List<MailThread>`.
* **ViewModels:**
    * ViewModels serving conversation-aware list views must source their data from
      `DefaultThreadRepository` and expose `StateFlow<ThreadDataState>`.
* **UI Composables:**
    * `ThreadListItem.kt`: Used for rendering `MailThread` objects in any conversation-aware list.
      Displays `mailThread.participantsSummary`.
    * `MessageListItem.kt`: Used for rendering individual `Message` objects within an opened thread
      or non-conversation views. Displays `message.senderName`/`message.senderAddress`.

## 4. Proposed Architecture & Data Flow (for Conversation-Aware Folder Views)

1. **UI Request (e.g., User opens Inbox):**
    * `MailScreen` -> `MailViewModel`
2. **ViewModel Request:**
    * `MailViewModel` calls `DefaultThreadRepository.setTargetFolderForThreads(account, folder)`.
3. **Repository (`DefaultThreadRepository.launchThreadFetchJobInternal`):**
    * a. **Fetch Initial Messages:** Calls `MailApiService.getMessagesForFolder(folderId, ...)` for
      the specific provider (Google/Microsoft). This returns `List<Message>` with `threadId`
      populated.
    * b. **Identify Unique Threads:** Extracts distinct `threadId`s from the initial messages.
    * c. **Fetch Full Conversations:** For each unique `threadId`, calls
      `MailApiService.getMessagesForThread(threadId, ...)` for the specific provider. **This call
      must retrieve all messages for the conversation, regardless of the folder they currently
      reside in, to enable true cross-folder threading.**
    * d. **Assemble `MailThread`:** For each conversation's `List<Message>`, calls the generic
      `assembleMailThread(threadId, messages, accountId)` function. This creates a `MailThread`
      object, populating `participantsSummary`, `subject`, `snippet`, `lastMessageDateTime`,
      `unreadMessageCount`, etc.
    * e. **Emit State:** Updates `_threadDataState` with `ThreadDataState.Success(List<MailThread>)`
      containing the assembled and sorted `MailThread` objects.
4. **ViewModel Observes State:**
    * `MailViewModel` collects the `List<MailThread>` from `threadDataState`.
5. **UI Renders:**
    * `MailScreen` (or specific folder screen) observes the `List<MailThread>`.
    * A `LazyColumn` iterates through the `MailThread` objects.
    * Each `MailThread` is rendered using the `ThreadListItem.kt` composable, which displays
      `mailThread.participantsSummary`.

## 5. Step-by-Step Plan

1. **Verify `DefaultThreadRepository` Logic:**
    * **Action:** Double-check that `DefaultThreadRepository.launchThreadFetchJobInternal` and
      `assembleMailThread` function exactly as described in the architecture above for both Google
      and Microsoft providers.
    * **Ensure:** `selectFields` in `getMessagesForFolder` includes necessary fields for initial
      discovery (like `threadId`/`conversationId`).
    * **Confirm:** `getMessagesForThread` in `GmailApiHelper` correctly fetches all messages for a
      thread.
    * **Confirm:** `getMessagesForThread` in `GraphApiHelper` correctly fetches all messages for a
      conversation **by querying the `/me/messages` endpoint (filtered by `conversationId`) and not
      an endpoint scoped to a specific folder (e.g., `/me/mailFolders/{folder-id}/messages`). This
      is crucial for cross-folder threading on Microsoft accounts.**

2. **Audit Folder View Data Sources:**
    * **Action:** Identify all UI screens/composables that display lists of emails from folders (
      Inbox, Sent, custom folders).
    * **For each screen:**
        * Determine which ViewModel provides its data.
        * Verify that this ViewModel sources its data from `DefaultThreadRepository` (specifically
          via `setTargetFolderForThreads` or `refreshThreads`).
    * **Remediation:** If any folder view is not using `DefaultThreadRepository` or is directly
      fetching/displaying `List<Message>` without going through the thread assembly process,
      refactor it to use `DefaultThreadRepository` and expect `ThreadDataState` containing
      `List<MailThread>`.

3. **Audit Folder View UI Rendering:**
    * **Action:** For each UI screen identified in Step 2:
        * Confirm it's designed to consume a `List<MailThread>` (or `ThreadDataState`).
        * Verify that its list rendering logic (e.g., `LazyColumn`) uses `ThreadListItem.kt` to
          display each `MailThread`.
    * **Remediation:** If a folder view is using `MessageListItem.kt` to render items from what
      should be a list of `MailThread`s, change it to use `ThreadListItem.kt`.

4. **Test Microsoft Account Folder Views:**
    * **Action:** Specifically test Inbox, Sent, and other folders for a Microsoft account.
    * **Verify:** Threaded items correctly show the `participantsSummary` (e.g., "Sender A, Sender
      B & 1 more") rather than just the sender of the latest message.
    * **Verify:** Single, non-threaded messages correctly show just their individual sender.

5. **Test Google Account Folder Views:**
    * **Action:** Perform the same tests as in Step 4 for a Google account to ensure no regressions
      and continued correct behavior.

6. **Verify Thread Detail View:**
    * **Action:** Open a thread from any folder list (Google and Microsoft).
    * **Verify:** The thread detail view correctly lists individual messages. This view *should* use
      `MessageListItem.kt` to display each message within the opened thread.

7. **Verify Non-Conversation Views (e.g., Search):**
    * **Action:** If the app has views that are intentionally flat lists of messages (e.g., some
      search result views), ensure they still function correctly and use `MessageListItem.kt` if
      appropriate for their design. The harmonization primarily targets conversation-aware folder
      views.

8. **Documentation & Code Cleanup:**
    * **Action:** Update any relevant internal documentation to reflect the harmonized data flow.
    * **Action:** Remove any redundant code paths that might have been used for provider-specific
      list handling before harmonization.

By following this plan, the application will achieve a more consistent and user-friendly email
experience across different providers, leveraging the robust threading logic already partially in
place. 