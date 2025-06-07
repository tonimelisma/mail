# QA.md: Functional Test Cases for Offline Capabilities

## Introduction

This document provides a set of manual, end-to-end functional test cases for Quality Assurance (QA) testers. The goal is to verify the core offline functionalities of the Melisma Mail application, ensuring a seamless user experience when connectivity is limited or unavailable, and that actions performed offline are correctly synced when connectivity is restored.

**General Preconditions for All Tests:**

*   The Melisma Mail application is installed on a test device (Android).
*   At least two email accounts are set up:
    *   One Gmail account.
    *   One Microsoft (Outlook.com/Office 365) account.
*   Ensure the app has had an initial sync for both accounts while online.
*   For "Offline" steps, enable Airplane Mode on the device or otherwise disable all network connectivity (Wi-Fi and mobile data).
*   For "Online" steps, disable Airplane Mode and ensure the device has a stable internet connection.
*   Have access to the web versions of Gmail and Outlook.com to verify server-side changes.
*   Familiarize yourself with the "Outbox" (if visible in UI) and how pending actions are typically displayed.

**Note to Tester:** Pay attention to UI feedback, error messages, and the state of messages/threads in the app versus the server state.

---

## Test Suite 1: Basic Offline Read and Folder Sync

**Objective:** Verify that previously synced emails and folders are accessible offline and that initial sync correctly populates local data.

**Test Case 1.1: View Synced Emails and Folders Offline**

*   **Preconditions:**
    *   App has synced folders and emails for both Gmail and Microsoft accounts while online.
*   **Steps:**
    1.  **Go Offline:** Enable Airplane Mode.
    2.  Open the Melisma Mail app.
    3.  Navigate to the Inbox of the Gmail account.
        *   **Expected:** Previously synced emails are listed and readable. Email content (body, basic headers) is displayed.
    4.  Navigate to a subfolder (e.g., "Sent" or a custom folder) of the Gmail account.
        *   **Expected:** Previously synced emails in that folder are listed and readable.
    5.  Repeat steps 3-4 for the Microsoft account.
        *   **Expected:** Same behavior as with the Gmail account.
    6.  **Go Online:** Disable Airplane Mode.
*   **Verification:**
    *   All previously synced content was viewable offline.

**Test Case 1.2: Initial Sync Verification (New Folder/Email on Server)**

*   **Preconditions:**
    *   App is online.
*   **Steps:**
    1.  Using the web interface for Gmail, create a new folder (e.g., "QATestFolderGmail") and move an unread email into it.
    2.  Using the web interface for Microsoft, create a new folder (e.g., "QATestFolderMS") and move an unread email into it.
    3.  In the Melisma Mail app, trigger a manual refresh/sync for the Gmail account (if available) or wait for the next scheduled sync.
        *   **Expected:** "QATestFolderGmail" appears in the folder list. The email moved into it is present and reflects its unread status.
    4.  Trigger a manual refresh/sync for the Microsoft account or wait.
        *   **Expected:** "QATestFolderMS" appears in the folder list. The email moved into it is present and reflects its unread status.
*   **Verification:**
    *   New folders and emails from the server are synced correctly into the app.

---

## Test Suite 2: Offline Actions - Message Operations

**Objective:** Verify that users can perform common message actions (mark read/unread, star, delete, move) offline and that these actions are synced to the server when online.

**Test Case 2.1: Mark Read/Unread Offline & Sync**

*   **Preconditions:**
    *   App has synced emails for both accounts.
*   **Steps:**
    1.  **Go Offline.**
    2.  Open an unread email in the Gmail account's Inbox.
        *   **Expected (App):** Email is marked as read locally.
    3.  Select a read email in the Gmail account's Inbox and mark it as unread.
        *   **Expected (App):** Email is marked as unread locally.
    4.  Repeat steps 2-3 for the Microsoft account.
        *   **Expected (App):** Similar local changes.
    5.  **Go Online.** Wait for sync to complete (observe `ActionUploadWorker` activity if possible, or allow a couple of minutes).
    6.  Check the Gmail web interface.
        *   **Expected (Server):** The first email is marked as read, the second as unread.
    7.  Check the Microsoft Outlook web interface.
        *   **Expected (Server):** The corresponding emails reflect the read/unread changes.
*   **Verification:**
    *   Offline read/unread status changes are reflected locally immediately.
    *   Changes are synced to both Gmail and Microsoft servers correctly.

**Test Case 2.2: Star/Unstar Message Offline & Sync**

*   **Preconditions:**
    *   App has synced emails.
*   **Steps:**
    1.  **Go Offline.**
    2.  Star an unstarred email in the Gmail account.
        *   **Expected (App):** Email is marked as starred locally.
    3.  Unstar a starred email in the Microsoft account.
        *   **Expected (App):** Email is marked as unstarred locally.
    4.  **Go Online.** Wait for sync.
    5.  Check Gmail web interface.
        *   **Expected (Server):** Email shows as starred.
    6.  Check Microsoft Outlook web interface.
        *   **Expected (Server):** Email shows as unstarred.
*   **Verification:**
    *   Offline star/unstar changes are reflected locally immediately.
    *   Changes are synced to both servers.

**Test Case 2.3: Delete Message Offline & Sync**

*   **Preconditions:**
    *   App has synced emails.
*   **Steps:**
    1.  **Go Offline.**
    2.  Delete an email from the Gmail account's Inbox.
        *   **Expected (App):** Email is removed from Inbox locally (or moved to a local "Trash" representation).
    3.  Delete an email from the Microsoft account's Inbox.
        *   **Expected (App):** Similar local change.
    4.  **Go Online.** Wait for sync.
    5.  Check Gmail web interface (Inbox and Trash).
        *   **Expected (Server):** Email is in Trash or permanently deleted, depending on app logic.
    6.  Check Microsoft Outlook web interface (Inbox and Deleted Items).
        *   **Expected (Server):** Email is in Deleted Items or permanently deleted.
*   **Verification:**
    *   Offline deletions are reflected locally.
    *   Deletions are synced to both servers.

**Test Case 2.4: Move Message Offline & Sync**

*   **Preconditions:**
    *   App has synced emails and at least two folders (e.g., Inbox, "Archive") for each account.
*   **Steps:**
    1.  **Go Offline.**
    2.  Move an email from Inbox to "Archive" in the Gmail account.
        *   **Expected (App):** Email is removed from Inbox and appears in "Archive" locally.
    3.  Move an email from Inbox to "Archive" in the Microsoft account.
        *   **Expected (App):** Similar local change.
    4.  **Go Online.** Wait for sync.
    5.  Check Gmail web interface (Inbox and "Archive").
        *   **Expected (Server):** Email is in "Archive".
    6.  Check Microsoft Outlook web interface (Inbox and "Archive").
        *   **Expected (Server):** Email is in "Archive".
*   **Verification:**
    *   Offline moves are reflected locally.
    *   Moves are synced to both servers.

---

## Test Suite 3: Offline Actions - Thread Operations (If Applicable)

**Objective:** Verify that users can perform thread-level actions (e.g., delete thread, move thread) offline and these sync correctly. (These tests assume the app supports thread-level actions distinct from single message actions).

**Test Case 3.1: Delete Thread Offline & Sync**

*   **Preconditions:**
    *   App has synced email threads.
*   **Steps:**
    1.  **Go Offline.**
    2.  Select and delete an entire email thread in the Gmail account.
        *   **Expected (App):** All messages in the thread are removed locally (or moved to Trash).
    3.  Repeat for a thread in the Microsoft account.
        *   **Expected (App):** Similar local change.
    4.  **Go Online.** Wait for sync.
    5.  Check Gmail web interface.
        *   **Expected (Server):** Thread is in Trash or deleted.
    6.  Check Microsoft Outlook web interface.
        *   **Expected (Server):** Thread is in Deleted Items or deleted.
*   **Verification:**
    *   Offline thread deletions are reflected locally.
    *   Thread deletions are synced.

**Test Case 3.2: Move Thread Offline & Sync**

*   **Preconditions:**
    *   App has synced email threads and multiple folders.
*   **Steps:**
    1.  **Go Offline.**
    2.  Move an entire email thread from Inbox to "Archive" in the Gmail account.
        *   **Expected (App):** Thread disappears from Inbox and appears in "Archive" locally.
    3.  Repeat for a thread in the Microsoft account.
        *   **Expected (App):** Similar local change.
    4.  **Go Online.** Wait for sync.
    5.  Check Gmail web interface.
        *   **Expected (Server):** Thread is in "Archive".
    6.  Check Microsoft Outlook web interface.
        *   **Expected (Server):** Thread is in "Archive".
*   **Verification:**
    *   Offline thread moves are reflected locally.
    *   Thread moves are synced.

---

## Test Suite 4: Outbox Functionality (Send Mail)

**Objective:** Verify that composing and sending emails offline places them in an outbox state and they are sent when connectivity returns. Verify attachment handling.

**Test Case 4.1: Send Email Offline (No Attachment) & Sync**

*   **Preconditions:**
    *   Device is online initially to load compose screen if needed.
*   **Steps:**
    1.  Start composing a new email using the Gmail account. Fill in To, Subject, Body.
    2.  **Go Offline.**
    3.  Send the email.
        *   **Expected (App):** Email is placed in a local "Outbox" or pending state. It should not appear in "Sent" yet. The compose window closes.
    4.  Repeat steps 1-3 for the Microsoft account.
        *   **Expected (App):** Similar behavior.
    5.  **Go Online.** Wait for sync (allow time for `ActionUploadWorker` to send).
    6.  Check Gmail web interface ("Sent" folder and recipient's inbox).
        *   **Expected (Server):** Email is in "Sent" and received by the recipient.
    7.  Check Microsoft Outlook web interface ("Sent Items" and recipient's inbox).
        *   **Expected (Server):** Email is in "Sent Items" and received by the recipient.
    8.  Check the app's "Outbox" (if visible) and "Sent" folder for both accounts.
        *   **Expected (App):** Emails are no longer in "Outbox" and appear in the "Sent" folder.
*   **Verification:**
    *   Offline emails are queued locally.
    *   Emails are sent successfully upon going online and correctly appear in "Sent" folders on server and app.

**Test Case 4.2: Send Email Offline (With Small Attachment) & Sync**

*   **Preconditions:**
    *   Have a small attachment file (e.g., a .txt or .jpg < 1MB) available on the device.
*   **Steps:**
    1.  Start composing a new email using the Gmail account. Fill in To, Subject, Body. Add the small attachment.
    2.  **Go Offline.**
    3.  Send the email.
        *   **Expected (App):** Email (with attachment) is placed in a local "Outbox" or pending state.
    4.  Repeat steps 1-3 for the Microsoft account using the same or a different small attachment.
        *   **Expected (App):** Similar behavior.
    5.  **Go Online.** Wait for sync.
    6.  Check Gmail web interface ("Sent" folder and recipient's inbox).
        *   **Expected (Server):** Email is in "Sent" with the attachment, and received by the recipient with the attachment.
    7.  Check Microsoft Outlook web interface ("Sent Items" and recipient's inbox).
        *   **Expected (Server):** Email is in "Sent Items" with the attachment, and received by the recipient with the attachment.
*   **Verification:**
    *   Offline emails with small attachments are queued.
    *   Emails and their attachments are sent successfully for both account types.

**Test Case 4.3: Send Email Offline (With Large Attachment - if supported by MS Graph plan)**
*(This test depends on whether large attachment handling via upload sessions is implemented and expected for Microsoft accounts as per OFFLINE.md)*

*   **Preconditions:**
    *   Have a larger attachment file (e.g., > 3MB, like a PDF or video) available.
*   **Steps:**
    1.  Start composing a new email using the Microsoft account. Fill in To, Subject, Body. Add the large attachment.
    2.  **Go Offline.**
    3.  Send the email.
        *   **Expected (App):** Email (with attachment) is placed in a local "Outbox".
    4.  **Go Online.** Wait for sync. (This might take longer).
    5.  Check Microsoft Outlook web interface ("Sent Items" and recipient's inbox).
        *   **Expected (Server):** Email is in "Sent Items" with the attachment, and received by the recipient with the attachment.
*   **Verification:**
    *   Offline email with a large attachment (for Microsoft) is queued.
    *   Email and attachment are sent successfully.

---

## Test Suite 5: Drafts with Attachments (Microsoft Account Focus)

**Objective:** Verify creating and updating drafts with attachments offline for Microsoft accounts.

**Test Case 5.1: Create Draft with Attachment Offline (Microsoft)**

*   **Preconditions:**
    *   Device online initially to load compose.
*   **Steps:**
    1.  Start composing a new email using the Microsoft account. Fill in To, Subject, Body. Add a small attachment.
    2.  **Go Offline.**
    3.  Save the email as a draft.
        *   **Expected (App):** Draft is saved locally and should be visible in the "Drafts" folder. The attachment should be associated with this local draft.
    4.  **Go Online.** Wait for sync.
    5.  Check Microsoft Outlook web interface ("Drafts" folder).
        *   **Expected (Server):** Draft appears in the "Drafts" folder on the server with the attachment.
    6.  Open the draft from the app's "Drafts" folder.
        *   **Expected (App):** Draft opens with all content and the attachment.
*   **Verification:**
    *   Draft with attachment can be saved offline.
    *   Draft (with attachment) syncs to the server.

**Test Case 5.2: Update Draft (Add/Remove Attachment) Offline & Sync (Microsoft)**

*   **Preconditions:**
    *   A draft with an attachment exists (synced from Test Case 5.1, or create a new one while online and then go offline).
*   **Steps:**
    1.  **Go Offline.**
    2.  Open the existing draft (with attachment) for the Microsoft account.
    3.  Remove the existing attachment.
    4.  Add a new, different small attachment.
    5.  Modify the body text.
    6.  Save the draft.
        *   **Expected (App):** Local draft is updated with new body text and new attachment; old attachment is gone.
    7.  **Go Online.** Wait for sync.
    8.  Check Microsoft Outlook web interface ("Drafts" folder).
        *   **Expected (Server):** Draft on the server reflects the new body text and has the new attachment; the original attachment is no longer there.
*   **Verification:**
    *   Offline modifications to drafts (including attachment changes) are saved locally.
    *   Changes sync to the server.

---

## Test Suite 6: REQ-SYNC-005: Auto-Refresh Message on View

**Objective:** Verify that stale messages are auto-refreshed when viewed online.

**Test Case 6.1: Stale Message Auto-Refresh**

*   **Preconditions:**
    *   App is online.
    *   An email exists in the Inbox that was synced/viewed more than 5 minutes ago (or configured staleness threshold).
    *   You can modify this email on the server (e.g., add a reply to it from another account, or edit its subject if it's a draft you sent to yourself).
*   **Steps:**
    1.  On the server (e.g., Gmail web), modify an existing email that is in the app's Inbox (e.g., mark it unread if it was read, or if it's part of a thread, have a new reply arrive in that thread). Make a note of its current state in the app.
    2.  Ensure the app is online.
    3.  Open the specific email (or its thread) in the Melisma Mail app.
        *   **Expected:** The app should detect the message/thread is stale. It should silently trigger a refresh. After a brief moment, the UI should update to show the latest state from the server (e.g., new unread status, new reply in thread). This should happen without manual refresh.
*   **Verification:**
    *   Viewing a stale message online triggers a silent refresh.
    *   The UI updates to show the latest message content/status.

**Test Case 6.2: No Refresh if Offline or Recent**

*   **Preconditions:**
    *   An email viewed/synced less than 5 minutes ago.
*   **Steps:**
    1.  Ensure the app is online.
    2.  Open an email that was recently synced/viewed (not stale).
        *   **Expected:** No automatic refresh attempt should be visibly triggered. The content remains as is.
    3.  **Go Offline.**
    4.  Open a stale email (viewed > 5 mins ago).
        *   **Expected:** No refresh attempt should be made as the device is offline. The stale content is shown.
    5.  **Go Online.**
*   **Verification:**
    *   No refresh occurs for recent messages.
    *   No refresh attempt occurs for stale messages if the device is offline.

---

## Test Suite 7: Conflict Resolution (Informal Checks)

**Objective:** Observe app behavior during simple conflict scenarios. (These are more exploratory).

**Test Case 7.1: Modify Same Email Offline and Online**

*   **Preconditions:**
    *   App has synced emails.
*   **Steps:**
    1.  Note the subject of an email in the app (e.g., Email-A for Gmail account).
    2.  **Go Offline.**
    3.  In the app, attempt an action that is queued, e.g., mark Email-A as unread (if it was read).
    4.  While still offline for the app, go to the Gmail web interface using a different device/browser. Mark Email-A as starred.
    5.  **Go Online** in the app. Wait for sync.
    6.  Observe Email-A in the app and on the server.
        *   **Expected:** What happens? Does the app's change (unread) get applied? Does the server's change (starred) get applied? Does one overwrite the other, or are both states merged if possible? (e.g., unread AND starred). The exact behavior depends on the conflict resolution strategy (server wins, client wins, last write wins, or merging). Document the observed behavior.
*   **Verification:**
    *   Document the app's behavior in this conflict scenario. The key is predictable and non-data-loss behavior where possible.

---

This list is not exhaustive but covers the primary functionalities related to recent offline work. Testers should also perform exploratory testing around edge cases, repeated actions, and different network conditions. 