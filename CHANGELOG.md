# Changelog

## [YYYY-MM-DD] - Critical Crash Hotfix

### Summary
Fixed a critical crash (`ForegroundServiceStartNotAllowedException`) that occurred when the application was backgrounded while a sync operation was in progress. This was caused by an attempt to start a foreground service from the background, which is not allowed on modern Android versions.

### Detailed Changes

*   **Removed Proactive Foreground Service Start:** The logic in `SyncLifecycleObserver` that attempted to start the `InitialSyncForegroundService` when the app entered the background (`onStop`) was removed. This was the direct cause of the crash.

### Code Smells & Shortcuts
*   This is a hotfix to prevent a crash. The underlying issue is that the app relies on long-running background work without a proper mechanism like push notifications to trigger it. The long-term solution is to implement a push-based sync system, but that effort was unsuccessful. This fix ensures app stability but means that long-running syncs may be interrupted by the OS when the app is backgrounded.

## [YYYY-MM-DD] - Connectivity & Reliability Enhancements

### Summary
Implemented a series of reliability improvements focused on making the application's background synchronization more robust and intelligent. The changes address how the app behaves under poor network conditions and ensure it complies with modern Android requirements for background work.

### Detailed Changes

1.  **Connectivity-Aware Sync Throttling:**
    *   The `ConnectivityHealthTracker` was made more sensitive to network failures by reducing its time window from 3 minutes to 1 minute and lowering the failure count thresholds for entering `DEGRADED` (2 failures) and `BLOCKED` (4 failures) states.
    *   The `SyncController` is now fully aware of the network's health. It will actively pause its main processing queue and its job-creation loop if the network state becomes `BLOCKED`, preventing spammy, failing network requests.
    *   A simple back-off mechanism was added. When the network is `DEGRADED`, the `SyncController` will introduce a 10-second delay after a failure to reduce pressure on the network.

2.  **Proactive Notification Permission:**
    *   To guarantee the reliability of the foreground service used for heavy sync tasks, the application now proactively requests the `POST_NOTIFICATIONS` permission on app startup for users on Android 13 and higher.
    *   This logic was centralized into an `onAppStart()` method in the `MainViewModel`, which is called from `MainActivity`, ensuring existing users are prompted correctly.

3.  **UI State Awareness:**
    *   The `MainViewModel` now observes the `ConnectivityHealthTracker`'s state and exposes it to the UI layer via `MainScreenState`. This makes the data available for future UI components to visualize the app's connectivity status (e.g., an "Offline" or "Limited Connectivity" banner).

### Deviations from Plan
The initial analysis was flawed due to not reading the full source code first. The original plan from `PLAN.md` was more relevant than first assumed. The implemented plan is a corrected version that is fully consistent with the application's true architecture, centered around the `SyncController`.

### Code Smells & Shortcuts
*   The delays in `SyncController` for throttling are hardcoded (e.g., `30_000ms`). A future improvement could be to use a configurable exponential backoff strategy.
*   The notification permission request is triggered on startup without a preceding rationale dialog. This is a functional but not ideal user experience and could be improved with a dedicated UI flow. 