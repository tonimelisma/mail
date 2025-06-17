# Changelog

## [Unreleased]

### Added
- **Sync Engine v2.1**: A more intelligent and resource-aware synchronization engine.
  - **Gatekeeper Infrastructure**: A system to conditionally block sync jobs based on system state (e.g., cache pressure, network availability).
  - **WorkScore Metric**: Each sync job now has a `workScore` to represent its cost, allowing for more granular control over background processing.
  - **Heuristic-Based Foreground Service**: The foreground service for sync is now started and stopped based on the total `workScore` of pending jobs, improving battery efficiency.
  - **Smarter Cache Eviction**: The cache eviction logic has been updated to remove items older than 90 days, but only if they haven't been accessed in the last 24 hours. The eviction process is now triggered at 98% cache usage or every 24 hours.
  - **Cache-Aware Backfill**: The historical message back-fill process now automatically pauses when cache pressure is high, preventing inefficient download/eviction cycles.
- **Bulk Content Download (Phase-1)**: Implemented foundational support for `BULK_FETCH_BODIES` and `BULK_FETCH_ATTACHMENTS` jobs.
  - Added DAO helpers to surface messages without bodies and attachments pending download.
  - Added handlers in `SyncController` queuing fine-grained download jobs.
- **Debug Logging Enhancements**: Added detailed Timber instrumentation to bulk handlers and job submission paths.

### Changed
- `SyncController` now uses the `