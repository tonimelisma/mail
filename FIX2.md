# **Melisma Mail - Remediation Plan, Phase 2 (FIX2.md)**

Version: 1.0  
Date: July 3, 2025  
Author: Gemini

## **1. Executive Summary**

This document outlines the next phase of work required to bring the Melisma Mail application to a production-ready state. While the work in `FIX.md` stabilized the core sync engine and fixed critical show-stopper bugs, significant technical debt and architectural flaws remain.

The two primary goals of this plan are:
1.  **Resolve the Critical Build Blocker:** The persistent KSP error in the `:core-db` module renders the application unbuildable and must be the top priority.
2.  **Fix the Data Model:** The application's data model for messages and folders is fundamentally flawed, containing conflicting one-to-many and many-to-many implementations. This must be corrected to prevent data corruption and support modern email features properly.

---

## **2. Priority 1: Unblock The Build**

### **BLOCKER 1: Resolve KSP `[MissingType]` Error**

**Problem:** The application is unbuildable due to a persistent KSP error: `[MissingType]: Element 'net.melisma.core_db.AppDatabase' references a type that is not present`. All attempts to fix this by correcting entities, DAOs, and TypeConverters have failed.

**Plan:**
This is a deep, unconventional error likely stemming from a complex interaction between dependencies.
1.  **Dependency Audit:** Systematically review the versions of all Gradle plugins and libraries, especially `KSP`, `Room`, and `Hilt`. Look for known incompatibilities. Consider upgrading or downgrading them one by one.
2.  **Minimal Reproducible Example:** Attempt to create a new, minimal project with only the `:core-db` module and its dependencies. Incrementally add entities and DAOs until the error reappears. This is the most reliable way to isolate the exact cause.
3.  **Gradle Deep Dive:** Run the build with `--stacktrace` and `--scan` to get more detailed output that might reveal a hidden dependency conflict or a misconfiguration in the build process.

---

## **3. Priority 2: Comprehensive Data Model & Architecture Fixes**

### **ARCH-1: Finalize the Message-Folder Many-to-Many Model**

**Problem:** The user correctly identified that the app's data model is confused. Gmail uses labels (a message can be in multiple folders/have multiple labels), but the code contains remnants of a one-to-many model (`folderId` on a message). This was partially fixed by introducing the `MessageFolderJunction` table, but the implementation is incomplete and inconsistent.

**Analysis of Gaps:**
*   **DAO Layer:** `MessageDao` still contains methods like `updateFolderIdForMessages` and `updateFolderId`, which are based on the incorrect one-to-many model. These methods directly modify a column that should not exist.
*   **Repository Layer:** The `moveThread` and `moveMessage` methods in the repositories currently perform a "replace" operation, which works for moving a message from one folder to another but doesn't support the full range of label operations (e.g., adding a second label, removing one of three labels).
*   **Sync Layer:** The logic in `SyncController` for processing incoming messages must be robust enough to reconcile a list of server-side labels with the entries in the `MessageFolderJunction` table, handling additions and removals correctly.
*   **Entity Layer:** The `MessageEntity` itself might still contain obsolete fields related to a single folder, which should be removed.

**Plan:**
1.  **Solidify the Database Schema:**
    *   Audit `MessageEntity.kt` and remove any lingering `folderId` or similar columns. The entity must be completely unaware of which folder(s) it belongs to.
    *   Confirm that `MessageFolderJunction.kt` is correctly defined with primary keys and foreign keys with `onDelete = CASCADE`.
2.  **Refactor the DAO (`MessageDao.kt`):**
    *   Delete all methods that operate on a single `folderId` within `MessageEntity` (e.g., `updateFolderId`, `updateFolderIdForMessages`).
    *   Create new, explicit methods for managing the junction table:
        *   `addLabel(messageId: String, folderId: String)`
        *   `removeLabel(messageId: String, folderId: String)`
        *   `replaceLabels(messageId: String, newFolderIds: List<String>)` (for sync reconciliation)
3.  **Refactor the Repositories (`DefaultMessageRepository`, `DefaultThreadRepository`):**
    *   Rewrite the `moveMessage` and `moveThread` methods. A "move" should be implemented as `removeLabel(messageId, oldFolderId)` followed by `addLabel(messageId, newFolderId)`.
    *   Add new repository methods to expose the full power of the M:M model, like `applyLabelToMessage`, `removeLabelFromMessage`, etc.
4.  **Refactor the `SyncController`:**
    *   When processing incoming message data, the controller must get the list of folder/label IDs from the server for each message and use the `replaceLabels` DAO method to ensure the local state in `MessageFolderJunction` perfectly mirrors the server state.

### **ARCH-2: Complete Outstanding Technical Debt**

**Problem:** Numerous smaller pieces of unfinished work, code smells, and `TODO`s remain, which collectively reduce the stability and maintainability of the codebase.

**Plan:**
1.  **Fix Failing Tests:** The entire test suite in `GmailApiHelperTest.kt` is disabled via `// TODO: Fix test...` comments. A CI/CD pipeline is useless without a working test suite. These tests must be updated to reflect the latest API and then re-enabled.
2.  **Resolve Valid `TODO`s:** Address the remaining `TODO` comments that were left in the codebase, such as:
    *   `MicrosoftAuthManager`: Consider what should happen on a failed token persistence.
    *   `ActiveGoogleAccountHolder`: Decide if the active account needs to be persisted across app restarts.
3.  **Wire up UI Error Handling:** The `SyncController` now exposes an `error` `StateFlow`, but no UI component is observing it. A global error-display mechanism (e.g., a Snackbar in the main activity) should be implemented to show these persistent sync errors to the user.
4.  **Finalize Attachment Handling:** The logic for *sending* attachments with a draft and *parsing* attachments on received messages is incomplete. This is a core email feature that needs to be finished.
5.  **Revisit Microsoft Mappers:** The `MicrosoftAccountMappers.kt` file was difficult to edit and may contain other subtle issues. It should be reviewed and cleaned up. 