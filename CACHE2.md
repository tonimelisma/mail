# **Melisma Mail - Caching & Offline-First Implementation Guide**

Version: 1.6 (Based on Code Review - May 27, 2024)
Date: May 27, 2024

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
    * `AppDatabase.kt` is at `version = 5`.
    * Entities `AccountEntity.kt`, `FolderEntity.kt`, and `MessageEntity.kt` are defined.
    * `AccountEntity` includes `val needsReauthentication: Boolean = false`.
    * `MessageEntity` includes `val needsSync: Boolean = false` and
      `val lastSyncError: String? = null`.
    * DAOs (`AccountDao`, `FolderDao`, `MessageDao`) are present.
    * TypeConverters (`WellKnownFolderTypeConverter`, `StringListConverter`) are present.
    * **Migrations Implemented:**
        * `MIGRATION_1_2`: Adds `messages` table.
        * `MIGRATION_2_3`: Adds `needsSync` to `messages` table.
        * `MIGRATION_3_4`: Adds `needsReauthentication` to `accounts` table.
      * `MIGRATION_4_5`: Adds `lastSyncError` to `messages` table.
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
          first, then enqueues `SyncMessageStateWorker` to handle the API synchronization.
        * `starMessage` (new method) updates `MessageEntity.isStarred` and sets `needsSync = true`
          in the DAO
          first, then enqueues `SyncMessageStateWorker` to handle the API synchronization.
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
      * **FIXED (Build Error):** The approach of using `IAccount.getId()` directly resolved previous
        compilation issues related to `getHomeAccountId().getIdentifier()` and MSAL v6.0.0.
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
      * **FIXED (Build Error):** An `Unresolved reference 'toDomainAccount'` was fixed by creating
        `backend-microsoft/src/main/java/net/melisma/backend_microsoft/mapper/DatabaseMappers.kt`
        with an `AccountEntity.toDomainAccount()` extension function.

### **C. Unimplemented Core Offline/Caching Features:**

* **WorkManager for "True Offline" Sync:**
    * `SyncMessageStateWorker.kt` **exists (structure implemented, DI for MailApiServices,
      ErrorMappers, Dispatcher, AccountRepository, AppDatabase added; API logic for
      markRead/starMessage implemented within worker; DAO updates for sync state handled by worker)
      **.
    * `DefaultMessageRepository.markMessageRead` (and `starMessage`) now enqueue
      `SyncMessageStateWorker` with corrected constants (`OP_MARK_READ`, `OP_STAR_MESSAGE`,
      `KEY_OPERATION_TYPE`) after local DAO update. The worker now fetches account details itself.
* **On-Demand Message Body Fetching:**
    * `MessageBodyEntity.kt` **STILL does not exist** (Mistakenly thought this was added, but it was
      `MessageBodyDao` DI).
    * `MessageBodyDao.kt` **exists and is now provided via Hilt DI** in `DatabaseModule.kt`.
    * No related `AppDatabase` migration for `MessageBodyEntity` yet.
* **Comprehensive Offline Writes:** Deleting, moving, composing/sending messages are not
  offline-first (mostly stubs in `DefaultMessageRepository`).
* **Attachment Handling:** No evidence of attachment entities, DAO, or download/caching logic.
* **Local Full-Text Search (FTS):** No evidence of `MessageFtsEntity` or FTS setup.

## **II. Unified List of Technical Debt and Other Challenges That Still Need To Be Fixed (Verified

May 27, 2024 - Updated after recent build fixes)**

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
      `IAccount.getId()` (Kotlin `this.id`) as the primary identifier. **VALIDATED (Build):** This
      approach fixed compilation issues. **ACTION:** The stability and sufficiency of
      `IAccount.getId()` as the canonical ID still needs to be validated during runtime testing.
   * **FIXED (Build Error):** Callback in `MicrosoftAuthManager.kt` corrected from `onAccountLoaded`
     to `onTaskCompleted`.

2. **`needsReauthentication` Flag - Implementation Status:**
    * **FIXED & VERIFIED (Build):** Flawed `overallApplicationAuthState` in
      `MicrosoftAccountRepository.kt` now correctly reflects the `needsReauthentication` state from
      `AccountDao`.
    * **`MicrosoftAccountMappers.kt` Default:** Defaults `Account.needsReauthentication` to `false`
      during initial mapping. Acceptable as dynamic status is managed by repository sync.

3. **API and Data Model Limitations (DESIGN/IMPLEMENTATION ISSUES):**
    * **API-Model Discrepancies:** Potential for incomplete cached data if `MailApiService` doesn't
      provide all fields defined in `core_data.model.Message`.
    * **Stubbed API Services (CODE ISSUE):** Key methods like `getMessageAttachments`,
      `downloadAttachment`, `createDraftMessage`, `updateDraftMessage` in
      `DefaultMessageRepository.kt` are stubs.
    * **`MailApiService` Pagination Limitation (DESIGN LIMITATION):** `getMessagesForFolder` only
      supports `maxResults`, preventing network-driven APPEND/PREPEND in `MessageRemoteMediator`.
   * **FIXED (Build Error):** Ktor `contentType` usage in `GraphApiHelper.kt` for `starMessage` was
     resolved by removing explicit import and call, relying on `ContentNegotiation`.
   * **FIXED (Build Error):** `Unresolved reference 'contentType'` in `GraphApiHelper.kt` (
     `mapGraphMessageToMessage`) fixed with explicit cast.

4. **Data Handling and Persistence Concerns (CODE/SCHEMA ISSUES):**
    * **Error Handling in Mappers (Date Parsing):** `MessageMappers.kt` (as per `CACHE.md`) date
      parsing defaults need careful UI handling.
    * **RESOLVED (Build & Code):** Missing `lastSyncError` in `MessageEntity.kt` (was already
      present, schema is fine). `MessageDao` has `updateLastSyncError` and `clearSyncState`.
    * **`DefaultMessageRepository.kt` snippet issue:** Corrected `fetchedMessage.snippet` to
      `fetchedMessage.bodyPreview`.
    * **`DefaultMessageRepository.kt` typo:** `inputDataBilder` instead of `inputDataBuilder` in
      `enqueueSyncMessageStateWorker` (lingering typo, did not break build).

5. **Offline Functionality Gaps (UNIMPLEMENTED FEATURES & RECENT PROGRESS):**
    * **WorkManager for "True Offline" Partially Implemented & Improved:**
        * `SyncMessageStateWorker.kt` DI setup is more complete. API call logic for mark read/star
          is present. Worker handles DAO updates for `needsSync` & `lastSyncError`.
        * `DefaultMessageRepository.markMessageRead` and `starMessage` method enqueue the worker
          with corrected constants.
        * **PENDING:** Robust error handling and retry policies in worker need more thorough
          implementation and testing.
    * **On-Demand Message Body Fetch Progress:**
        * `MessageBodyDao.kt` exists and is provided via Hilt.
        * **PENDING:** `MessageBodyEntity.kt` creation, `AppDatabase` migration for it, and related
          ViewModel/UseCase/Repository logic for actual fetching/saving message bodies.
    * **Other Offline Writes Not Implemented:** `deleteMessage`, `moveMessage`, etc., are stubs.

6. **UI and User Experience Issues (RUNTIME/UI ISSUES):**
    * **Stale Data Presentation During Auth Errors:** (As per `CACHE.md`). Needs runtime
      verification and fixing, possibly linked to `overallApplicationAuthState` issues.
    * **Missing `ButtonPrimary` Composable Styling:** Standard `Button` used as replacement.
    * **Paging 3 UI Loading/Error States:** Need thorough runtime testing for all conditions.

7. **Code Health and Maintenance (CODE ISSUES):**
    * **Log Spam:** Extensive diagnostic logging should be reviewed.
    * **Lint Warning in `DefaultAccountRepository.kt`:** Accepted cast warning persists.

## **III. Unified List of Work That Is Still To Be Done to Achieve the Vision (Verified May 27,

2024 - Updated after recent build fixes)**

This list outlines the necessary steps to realize the full offline-first caching vision.

1. **Stabilize Core Authentication & Account Management (Microsoft Focus - URGENT):**
    * **Resolve MSAL Cache/Token Runtime Issues:** Investigate and fix MSAL runtime errors (e.g.,
      `AdalKey` issues).
    * **Validate `IAccount.getId()` Stability (Runtime):** Confirm through runtime testing that
      `IAccount.getId()` provides a stable and correct canonical identifier for Microsoft accounts
      with MSAL v6.0.0.
    * **Ensure Timely Active Microsoft Account ID:** Refactor calling logic for
      `ActiveMicrosoftAccountHolder.setActiveMicrosoftAccountId`.
    * **Validate/Refine `MicrosoftAuthManager.signOut()`:** Thoroughly runtime test its robustness.
    * ~~**FIXED (Build Error):** Callback in `MicrosoftAuthManager.kt` corrected.~~
    * ~~**FIXED (Build Error):** `MicrosoftAccountRepository.kt` `toDomainAccount` mapping.~~

2. **Fully Implement and Test `needsReauthentication` Flag Logic (High Priority):**
    * **DONE & VERIFIED (Build):** Fix `MicrosoftAccountRepository.overallApplicationAuthState`.
    * **Verify `MicrosoftAccountRepository.syncAccount` Robustness:** Ensure it correctly calls
      `markAccountForReauthentication` for all relevant MSAL exceptions and clears the flag on
      success. (Largely verified, ongoing testing focus).
    * **UI Testing for Re-auth Prompts.**

3. **Complete, Test, and Refine Paging 3 Message List Implementation:**
    * Perform thorough runtime testing of message list loading, pull-to-refresh, error states, and
      stale data handling.

4. **Implement "True Offline" Operations with WorkManager (Incremental Rollout):**
    * **Phase 1: Mark Read/Unread & Star/Unstar:**
        * **DONE:** Database Schema: `lastSyncError: String? DEFAULT NULL` in `MessageEntity.kt` (
          verified present).
        * **DONE:** Room migration (e.g., `MIGRATION_4_5`) in `AppDatabase.kt` (verified present).
        * **DONE (Significant Progress):** `SyncMessageStateWorker.kt`: DI setup, API call logic for
          mark read/star, DAO updates for sync state. Constants in `DefaultMessageRepository`
          corrected.
        * **PENDING/REFINEMENT:** More robust error handling and retry policies within
          `SyncMessageStateWorker.kt`.
        * **DONE:** Refactor `DefaultMessageRepository.markMessageRead()` (and new `starMessage()`):
          Enqueues worker.
    * **Phase 2: Other Write Operations:** Extend WorkManager pattern for delete, move,
      compose/send (with outbox table).

5. **Implement On-Demand Fetching & Caching for Full Content (Incremental Rollout):**
    * **Phase 1: Message Bodies:**
        * **PENDING:** **Create `MessageBodyEntity.kt`**.
        * **PENDING:** **Update `AppDatabase.kt`:** Add entity, DAO method, and new Room migration.
        * **DONE:** **Implement `MessageBodyDao.kt` and provide via Hilt DI.**
        * **PENDING:** Implement ViewModel, UseCase, Repository logic for fetching, saving, and
          observing message bodies.
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
   * Implement stubbed API service methods (e.g., `getMessageAttachments`, `starMessage` in
     `MailApiService` if needed).
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
  * **WorkManager Best Practices for Sync:** (Currently being applied for basic structure) Input
    data, Hilt DI for workers, error handling, retry policies, unique work.
  * **`MailApiService` for Star/Unstar:** Verify/add `starMessage` method to the interface and
    implementations.

This rewritten `CACHE2.md` should now accurately reflect the project's state based on our detailed
analysis and direct code examination. 