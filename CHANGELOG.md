# Changelog

## [Unreleased]

### Added
- **Sync Engine v2.1**: A more intelligent and resource-aware synchronization engine.
  - **Gatekeeper Infrastructure**: A system to conditionally block sync jobs based on system state (e.g., cache pressure, network availability).
  - **WorkScore Metric**: Each sync job now has a `workScore` to represent its cost, allowing for more granular control over background processing.
  - **Heuristic-Based Foreground Service**: The foreground service for sync is now started and stopped based on the total `workScore` of pending jobs, improving battery efficiency.
  - **Smarter Cache Eviction**: The cache eviction logic has been updated to remove items older than 90 days, but only if they haven't been accessed in the last 24 hours. The eviction process is now triggered at 98% cache usage or every 24 hours.
  - **Cache-Aware Backfill**: The historical message back-fill process now automatically pauses when cache pressure is high, preventing inefficient download/eviction cycles.

### Changed
- `SyncController` now uses the `Gatekeeper` infrastructure and `workScore` to manage the synchronization queue.
- `InitialSyncForegroundService` lifecycle is now tied to the `totalWorkScore` in the `SyncController`.

### Fixed
- Build error in `BackfillJobProducer` caused by a duplicate variable declaration. 