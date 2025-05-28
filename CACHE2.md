# **Melisma Mail - Caching & Offline-First Implementation Guide**

Version: 1.5 (Based on Code Review - May 26, 2024)
Date: May 26, 2024

## **0. Vision & Introduction**

This document outlines the architecture for Melisma Mail as an **offline-first application**. The
core principle is that the local Room database is the **Single Source of Truth (SSoT)** for all
application data. The UI reads exclusively from the database, ensuring a responsive and consistent
experience, even when offline. Network operations are treated as synchronization tasks that update
this local database.

## **I. Current Codebase State (As of Analysis on May 26, 2024)**

This section reflects the validated state of the codebase after direct inspection and recent
changes.

### **A. Core Caching & SSoT Foundation:**

* **`:core-db` Module & Database:**
    * `AppDatabase.kt` is at `version = 4`.
    * Entities `AccountEntity.kt`, `FolderEntity.kt`, and `MessageEntity.kt` are defined.
    * `AccountEntity` includes `val needsReauthentication: Boolean = false`.
    * `MessageEntity` includes `val needsSync: Boolean = false` but is **MISSING**
      `lastSyncError: String?`.
    * DAOs (`AccountDao`, `FolderDao`, `MessageDao`) are present.
    * TypeConverters (`WellKnownFolderTypeConverter`, `StringListConverter`) are present.
    * **Migrations Implemented:**
        * `MIGRATION_1_2`: Adds `messages` table.
        * `MIGRATION_2_3`: Adds `needsSync` to `messages` table.
        * `MIGRATION_3_4`: Adds `needsReauthentication` to `accounts` table.
        * **MISSING MIGRATION** for `messages.lastSyncError`.
        * **MISSING MIGRATION** for `MessageBodyEntity` (as entity itself is missing).

* **`:data` Module Repositories as Synchronizers:**
    * `DefaultAccountRepository.kt`:
        * Its `init` block correctly observes `AccountDao` for `overallApplicationAuthState`,
          considering the `needsReauthentication` flag from the DAO.
        * `syncGoogleAccount` method correctly sets/clears `needsReauthentication` in DAO based on
          `GoogleGetTokenResult`.
        * `markAccountForReauthentication` method is implemented and updates the DAO.
        * Delegates to `MicrosoftAccountRepository` for Microsoft account sync.
    * `DefaultMessageRepository.kt`:
        * `markMessageRead` updates `MessageEntity.isRead` and sets `needsSync = true` in the DAO
          first. It then has a **TODO** for WorkManager and currently proceeds with a direct API
          call (updating `needsSync` to `false` on API success).
        * Other write operations (`deleteMessage`, `moveMessage`) are stubs.
    * `DefaultFolderRepository.kt`: (Assumed to be largely functional per earlier `CACHE.md`
      versions, focusing on SSoT from DB for folders - not re-validated in this pass).

* **Paging 3 for Message Lists:**
    * `MessageRemoteMediator.kt`:
        * Correctly uses `MailApiService.getMessagesForFolder` (which only supports `maxResults`)
          for `LoadType.REFRESH`.
        * Clears and inserts messages into `MessageDao` within a transaction.
        * Returns `endOfPaginationReached = true` for REFRESH, PREPEND, and APPEND, reflecting API
          limitations.
    * `MailApiService.kt` interface's `getMessagesForFolder` signature confirmed to only accept
      `maxResults`, no page tokens.

### **B. Authentication Layer State:**

* **Google Authentication (`:backend-google`):**
    * `GoogleAuthManager.kt` and `DefaultAccountRepository.syncGoogleAccount` appear to handle token
      refresh and updating `needsReauthentication` flag correctly.

* **Microsoft Authentication (`:backend-microsoft` - MSAL Version 6.0.0):**
    * `MicrosoftAuthManager.kt`:
        * `signOut` method uses `CompletableDeferred` to bridge the MSAL callback; the "TEMPORARILY
          SIMPLIFIED" comment persists, suggesting it might not be the final intended robust
          version.
        * MSAL PII logging is enabled.
    * `MicrosoftAccountMappers.kt`:
        * Now uses `IAccount.getId()` (Kotlin property `this.id`) as the primary source for
          `Account.id`, falling back to an `oid` claim. The Javadoc for MSAL v6.0.0's
          `IAccount.getId()` states it returns "the OID of the account in its home tenant."
        * **REVISED UNDERSTANDING:** The previous "CRITICAL" point about
          `getHomeAccountId().getIdentifier()` not being used is now understood as likely due to
          this method path not being available/resolvable as expected on `IAccount` or casted
          `IMultiTenantAccount` within the project's MSAL 6.0.0 version and build environment. The
          current implementation uses the best available identifier from `IAccount` directly.
          Further investigation into `IAccount.getId()` stability or an MSAL version upgrade would
          be needed if `getHomeAccountId().getIdentifier()` is strictly required.
        * Defaults `Account.needsReauthentication` to `false` with a TODO (this is for initial
          mapping; `syncAccount` handles dynamic state).
    * `ActiveMicrosoftAccountHolder.kt`: Loads/saves active ID from SharedPreferences; class itself
      seems okay, but timing of its usage is key.
    * `MicrosoftAccountRepository.kt`:
        * `syncAccount` method calls its *own* `markAccountForReauthentication` (which updates DAO)
          when MSAL silent token refresh fails or requires UI.
        * **FIXED:** Its `overallApplicationAuthState` Flow now correctly reads
          `needsReauthentication` status from `AccountDao` for Microsoft accounts, combined with
          `MicrosoftAuthManager.msalAccounts`. It no longer uses hardcoded `false` checks.

### **C. Unimplemented Core Offline/Caching Features:**

* **WorkManager for "True Offline" Sync:**
    * `SyncMessageStateWorker.kt` **does not exist**.
    * `MessageEntity.lastSyncError` field and its DB migration **are missing**.
    * `DefaultMessageRepository.markMessageRead` has a TODO and falls back to direct API call.
* **On-Demand Message Body Fetching:**
    * `MessageBodyEntity.kt` **does not exist**.
    * No related DAO or `AppDatabase` migration for message bodies.
* **Comprehensive Offline Writes:** Deleting, moving, composing/sending messages are not
  offline-first (mostly stubs in `DefaultMessageRepository`).
* **Attachment Handling:** No evidence of attachment entities, DAO, or download/caching logic.
* **Local Full-Text Search (FTS):** No evidence of `MessageFtsEntity` or FTS setup.

## **II. Unified List of Technical Debt and Other Challenges That Still Need To Be Fixed (Verified
May 26, 2024)**

1. **MSAL Authentication Instability & Related Issues:**
    * **Token Cache Corruption/Decryption Failures (Runtime Issue):** `CACHE.md` (previous versions)
      detailed runtime MSAL errors (`AdalKey` decryption, deserialization). The cause needs to be
      found and fixed.
    * **Delayed Active Account ID for Token Refresh (Runtime/Logic Issue):** The
      `ActiveMicrosoftAccountHolder` might not have the active ID when Ktor needs it. Timing of
      `setActiveMicrosoftAccountId` calls needs review.
    * **`MicrosoftAuthManager.signOut()` Robustness:** The current `CompletableDeferred`-based
      `signOut` needs rigorous testing to ensure it fully handles MSAL account removal in all
      scenarios, despite the "TEMPORARILY SIMPLIFIED" comment.
    * **Microsoft Account ID Stability (MSAL v6.0.0):** The `MicrosoftAccountMappers.kt` now uses
      `IAccount.getId()` (Kotlin `this.id`) as the primary identifier, based on MSAL 6.0.0
      Javadoc ("OID of the account in its home tenant"). This approach fixed compilation issues with
      `getHomeAccountId().getIdentifier()`. **ACTION:** The stability and sufficiency of
      `IAccount.getId()` as the canonical ID needs to be validated during testing. If insufficient,
      investigation into MSAL library upgrade or alternative strategies for a globally unique ID
      will be necessary.

2. **`needsReauthentication` Flag - Implementation Status:**
    * **FIXED: Flawed `overallApplicationAuthState` in `MicrosoftAccountRepository.kt`:** This Flow
      now correctly reflects the `needsReauthentication` state from `AccountDao` for Microsoft
      accounts. UI should continue to rely solely on
      `DefaultAccountRepository.overallApplicationAuthState` for the global application state.
    * **`MicrosoftAccountMappers.kt` Default:** Defaults `Account.needsReauthentication` to `false`
      during initial mapping from `IAccount`. This is acceptable as dynamic re-authentication status
      is managed by repository sync logic and stored in the DAO.

3. **API and Data Model Limitations (DESIGN/IMPLEMENTATION ISSUES):**
    * **API-Model Discrepancies:** Potential for incomplete cached data if `MailApiService` doesn't
      provide all fields defined in `core_data.model.Message`.
    * **Stubbed API Services (CODE ISSUE):** Key methods like `getMessageAttachments`,
      `downloadAttachment`, `createDraftMessage`, `updateDraftMessage` in
      `DefaultMessageRepository.kt` are stubs.
    * **`MailApiService` Pagination Limitation (DESIGN LIMITATION):** `getMessagesForFolder` only
      supports `maxResults`, preventing network-driven APPEND/PREPEND in `MessageRemoteMediator`.

4. **Data Handling and Persistence Concerns (CODE/SCHEMA ISSUES):**
    * **Error Handling in Mappers (Date Parsing):** `MessageMappers.kt` (as per `CACHE.md`) date
      parsing defaults need careful UI handling. Consider nullable `Long?` for
      `MessageEntity.receivedTs`.
    * **Missing `lastSyncError` in `MessageEntity.kt` (SCHEMA/CODE ISSUE):** This field and its DB
      migration are missing, limiting offline sync error tracking.

5. **Offline Functionality Gaps (UNIMPLEMENTED FEATURES):**
    * **WorkManager for "True Offline" Not Implemented:** `SyncMessageStateWorker.kt` doesn't exist.
      `DefaultMessageRepository.markMessageRead` relies on a direct API call fallback.
    * **On-Demand Message Body Fetch Not Implemented:** `MessageBodyEntity.kt`, its DAO, and
      migration are missing.
    * **Other Offline Writes Not Implemented:** `deleteMessage`, `moveMessage`, etc., are stubs.

6. **UI and User Experience Issues (RUNTIME/UI ISSUES):**
    * **Stale Data Presentation During Auth Errors:** (As per `CACHE.md`). Needs runtime
      verification and fixing, possibly linked to `overallApplicationAuthState` issues.
    * **Missing `ButtonPrimary` Composable Styling:** Standard `Button` used as replacement.
    * **Paging 3 UI Loading/Error States:** Need thorough runtime testing for all conditions.

7. **Code Health and Maintenance (CODE ISSUES):**
    * **Log Spam:** Extensive diagnostic logging should be reviewed.
    * **Lint Warning in `DefaultAccountRepository.kt`:** Accepted cast warning persists.

## **III. Unified List of Work That Is Still To Be Done to Achieve the Vision (Verified May 26,
2024)**

This list outlines the necessary steps to realize the full offline-first caching vision.

1. **Stabilize Core Authentication & Account Management (Microsoft Focus - URGENT):**
    * **Resolve MSAL Cache/Token Runtime Issues:** Investigate and fix MSAL runtime errors (e.g.,
      `AdalKey` issues).
    * **Validate `IAccount.getId()` Stability:** Confirm through testing that `IAccount.getId()` (as
      used in `MicrosoftAccountMappers.kt`) provides a stable and correct canonical identifier for
      Microsoft accounts with MSAL v6.0.0. If not, research alternatives or MSAL upgrade paths.
    * **Ensure Timely Active Microsoft Account ID:** Refactor calling logic for
      `ActiveMicrosoftAccountHolder.setActiveMicrosoftAccountId`.
    * **Validate/Refine `MicrosoftAuthManager.signOut()`:** Thoroughly runtime test its robustness.

2. **Fully Implement and Test `needsReauthentication` Flag Logic (High Priority):**
    * **DONE: Fix `MicrosoftAccountRepository.overallApplicationAuthState`**.
    * **Verify `MicrosoftAccountRepository.syncAccount` Robustness:** Ensure it correctly calls
      `markAccountForReauthentication` for all relevant MSAL exceptions and clears the flag on
      success. (Largely verified, ongoing testing focus).
    * **UI Testing for Re-auth Prompts.**

3. **Complete, Test, and Refine Paging 3 Message List Implementation:**
    * Perform thorough runtime testing of message list loading, pull-to-refresh, error states, and
      stale data handling.

4. **Implement "True Offline" Operations with WorkManager (Incremental Rollout):**
    * **Phase 1: Mark Read/Unread & Star/Unstar:**
        * **Database Schema:** Add `lastSyncError: String? DEFAULT NULL` to `MessageEntity.kt`.
          Create and add the corresponding Room migration (e.g., `MIGRATION_4_5`) in
          `AppDatabase.kt`.
        * **Create `SyncMessageStateWorker.kt`:** Implement worker logic (API call, DAO updates for
          `needsSync` & `lastSyncError`, retry policies).
        * **Refactor `DefaultMessageRepository.markMessageRead()` (and new `starMessage()`):**
          Remove direct API call fallback. Enqueue `SyncMessageStateWorker` after local DAO update.
    * **Phase 2: Other Write Operations:** Extend WorkManager pattern for delete, move,
      compose/send (with outbox table).

5. **Implement On-Demand Fetching & Caching for Full Content (Incremental Rollout):**
    * **Phase 1: Message Bodies:**
        * **Create `MessageBodyEntity.kt`**.
        * **Update `AppDatabase.kt`:** Add entity, DAO method, and new Room migration (e.g.,
          `MIGRATION_5_6`).
        * **Implement `MessageBodyDao.kt`**.
        * **Implement ViewModel, UseCase, Repository logic** for fetching, saving, and observing
          message bodies.
    * **Phase 2: Attachments (Longer Term):** Design and implement attachment caching.

6. **Enhance API Capabilities and Advanced Synchronization (Longer Term):**
    * Modify `MailApiService.getMessagesForFolder` to support token-based pagination.
    * Update `MessageRemoteMediator` for network-driven APPEND if API is enhanced.
    * Implement Smart Folder Sync Strategy.
    * Implement Conflict Resolution.

7. **Improve Data Management, Search, and Performance (Longer Term):**
    * Implement Database Cleanup/Pruning.
    * Integrate Local Full-Text Search (FTS5 with Room, new `MessageFtsEntity`).
    * Implement Intelligent Pre-fetching.

8. **Address Remaining General Technical Debt (Ongoing):**
    * Resolve API-Model Discrepancies.
    * Implement stubbed API service methods (e.g., `getMessageAttachments`).
    * Refine error handling in Mappers (e.g., date parsing).
    * Review and reduce log spam.
    * Decide on `ButtonPrimary` styling.

## **IV. Soft Spots, Unknowns, and Research Needs (Post Code Review - Updated May 26, 2024)**

* **Soft Spots:**
    * **MSAL Integration Robustness (General):** Runtime errors (`AdalKey`), `signOut` validation.
    * **Stability of `IAccount.getId()` as Canonical ID (MSAL 6.0.0):** Needs validation. The
      previous concern about `getHomeAccountId()` is now understood as an API availability issue
      with MSAL 6.0.0 in this project.
    * **Paging/RemoteMediator Error Handling:** Full spectrum of runtime error handling.

* **Unknowns (Without Further Code Reading/Runtime Info):**
    * **REVISED: Root cause of `getHomeAccountId().getIdentifier()` being unusable:** Now strongly
      suspected to be due to API differences/availability in the project's MSAL 6.0.0 version
      compared to Javadoc that described it.
    * Exact trigger for MSAL `AdalKey` errors.
    * Implementations of `MicrosoftTokenPersistenceService.kt` (partially reviewed),
      `GoogleTokenPersistenceService.kt`, `GmailApiHelper`, `GraphApiHelper`.
    * UI reaction logic to `OverallApplicationAuthState`.

* **Research/Investigation Needs:**
    * **MSAL Best Practices (v6.0.0 focus):** Cache management, `AdalKey` resolution. Verifying the
      precise nature and stability guarantees of `IAccount.getId()` in MSAL v6.0.0.
    * **MSAL Upgrade Path (If Necessary):** If `IAccount.getId()` proves insufficient, research
      implications and benefits of upgrading MSAL to a version that reliably provides
      `getHomeAccountId().getIdentifier()`.
    * **Ktor Authentication with MSAL:** Timing of active account ID for token provider.
    * **WorkManager Best Practices for Sync.**

This rewritten `CACHE2.md` should now accurately reflect the project's state based on our detailed
analysis and direct code examination. 