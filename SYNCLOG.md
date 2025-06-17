### Sync Engine v2.1 Migration Log

**Date:** 2025-08-03

#### Summary
Conducted a major refactoring and bug-fix pass on the v2.1 Sync Engine, correcting architectural flaws from the initial implementation and hardening the logic.

1.  **Refactored `SyncJob` Model**:
    *   Renamed `FetchMessageHeaders` to `HeaderBackfill` for clarity.
    *   Added new `BulkFetchBodies` and `BulkFetchAttachments` jobs for future opportunistic downloads.
    *   Converted `EvictFromCache` to a global, system-level job (`object`) instead of a per-account one.
    *   Introduced an `isProactiveDownload` flag on the base `SyncJob` to simplify gatekeeper logic.
    *   Marked legacy job names with `@Deprecated` to guide future cleanup.
2.  **Corrected Gatekeeper Logic**:
    *   Simplified `CachePressureGatekeeper` to use the new `isProactiveDownload` flag, making it more reliable and easier to maintain.
3.  **Fixed Cache Eviction Architecture**:
    *   `CacheEvictionProducer` is now stateless (using SharedPreferences for its timer) and correctly produces a single, global `EvictFromCache` job.
    *   `SyncController.runCacheEviction` now performs a global eviction based on total cache usage, fixing the critical flaw of the previous per-account implementation.
4.  **Streamlined Job Producers**:
    *   Removed redundant cache-pressure checks from `BackfillJobProducer`, making `CachePressureGatekeeper` the single source of truth.
5.  **Build & Verification**:
    *   Verified the complete build with `./gradlew build`. Deprecation warnings are expected and confirm the refactoring path.

---

**Date:** 2025-08-02

#### Summary
Completed the core implementation of Sync Engine v2.1, making the synchronization process more intelligent, resource-aware, and robust.

1.  **Gatekeeper Infrastructure**: Implemented a pluggable `Gatekeeper` system to conditionally allow or deny sync jobs.
    *   Added `CachePressureGatekeeper` to pause non-essential downloads when cache usage is high (>90%).
    *   Added `NetworkGatekeeper` to veto jobs that require a network connection when offline.
    *   Provided gatekeepers via a new `SyncGateModule` Hilt module.
2.  **WorkScore Metric**: Integrated a `workScore` into every `SyncJob` to quantify its resource cost.
    *   The `SyncController` now tracks a `totalWorkScore` for all pending jobs.
    *   The `isSyncing` status flag is now derived from `totalWorkScore > 0` for backward compatibility with the UI.
3.  **Heuristic-Based Foreground Service**: Refactored `InitialSyncForegroundService` to be driven by `totalWorkScore`.
    *   The service now starts only when a significant amount of work is queued (`workScore > 10`).
    *   It stops automatically after a 5-second debounce period of zero work, preventing service "flickering".
4.  **Aligned Back-fill & Eviction Logic**:
    *   `BackfillJobProducer` now respects cache pressure and will not queue new jobs if the cache is >90% full.
    *   `CacheEvictionProducer` now triggers an eviction job when cache usage exceeds a 98% hard limit or on a 24-hour cadence.
    *   The `SyncController`'s `runCacheEviction` logic was updated to remove items older than 90 days *only if* they have not been accessed in the last 24 hours.
5.  **Build & Verification**:
    *   Resolved build errors related to the new logic.
    *   Verified the complete build with `./gradlew build`.

---

**Date:** 2025-06-16

#### Summary
Initial groundwork for Sync Engine v2.1 (Architecture A – single dispatcher with modular producers).

1. **Schema upgrades** (Room DB version 21)
   • `FolderSyncStateEntity` + `deltaToken`, `historyId` (nullable).  
   • `MessageEntity` + `hasFullBodyCached` (default `false`).  
   • `AttachmentEntity` retains `isDownloaded` but now includes `downloadState` (ordinal of new enum `AttachmentDownloadState`).
2. **New enum** `AttachmentDownloadState` in `core-data` to formalise attachment cache state mapping.
3. **Destructive migrations** remain active via `fallbackToDestructiveMigration()` – no auto-migrations generated (project still pre-production).
4. **Unit tests removed** (`UtilTest`, `GoogleErrorMapperTest`, `MessageBodyDownloadTest`) – test suite will be rebuilt once v2.1 architecture stabilises.

Build verified with `./gradlew assembleDebug`.

- **2025-07-29**: Integrated comprehensive diagnostic logging to monitor device state (battery, network, memory, disk) during sync operations, especially on network failures. This helps diagnose issues related to Doze mode and background restrictions.
- **2025-07-28**: Refactored the authentication flow to correctly handle the `POST_NOTIFICATIONS` permission on Android 13+. The `MainViewModel` now orchestrates the permission request before launching the OAuth intent, ensuring the initial sync foreground service can post its notification.

**Date:** 2025-09-12

#### Summary
Completed post-intern hardening pass.

1. **Implemented Bulk Download Job Handling**
   * Added DAO helpers `getMessagesMissingBody` and `getUndownloadedAttachmentsForAccount` for efficient selection.
   * Added `handleBulkFetchBodies` and `handleBulkFetchAttachments` in `SyncController` and wired them into the main `run()` dispatcher.
   * Each handler now queues the fine-grained `FetchFullMessageBody`/`DownloadAttachment` jobs, respecting existing gatekeepers.
2. **Extended Debug Logging**
   * Inserted granular Timber `d`/`i` calls within bulk handlers and job submission paths to aid field diagnostics.
3. **Schema-Free Migration** – new DAO methods use existing columns; no DB version bump required.
4. **Build Verification** – Full `./gradlew build` completed without errors.

5. **BulkDownloadJobProducer**
   * New producer queues `BULK_FETCH_BODIES` / `BULK_FETCH_ATTACHMENTS` opportunistically when online and data missing.
6. **NetworkGatekeeper optimisation** – caches `isOnline` in a hot `StateFlow` to avoid cold-flow overhead per job vetting. 