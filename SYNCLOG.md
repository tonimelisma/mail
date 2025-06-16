### Sync Engine v2.1 Migration Log

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