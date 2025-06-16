### Sync Engine v2.1 Migration Log

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

- **2025-08-01**: Implemented Sync Engine v2.1.
  - Introduced `Gatekeeper` infrastructure (`CachePressureGatekeeper`, `NetworkGatekeeper`) to veto jobs based on system state.
  - Added `workScore` to `SyncJob` to quantify job cost.
  - `SyncController` now tracks `totalWorkScore` and derives `isSyncing` state from it for backward compatibility.
  - `InitialSyncForegroundService` now starts/stops based on `totalWorkScore` with a debounce, making it more resource-efficient.
  - `BackfillJobProducer` now pauses when cache pressure is high.
  - `CacheEvictionProducer` now triggers on a 98% hard limit or every 24 hours.
  - `SyncController.runCacheEviction` logic updated to evict items >90 days old with a 24-hour access grace period.
  - Added Hilt modules for providing `Gatekeeper`s and `JobProducer`s.
- **2025-07-29**: Integrated comprehensive diagnostic logging to monitor device state (battery, network, memory, disk) during sync operations, especially on network failures. This helps diagnose issues related to Doze mode and background restrictions.
- **2025-07-28**: Refactored the authentication flow to correctly handle the `POST_NOTIFICATIONS` permission on Android 13+. The `MainViewModel` now orchestrates the permission request before launching the OAuth intent, ensuring the initial sync foreground service can post its notification. 