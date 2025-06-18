# Changelog

## 2025-10-23 – Foreground-sync escalation & notification permission

* Lowered work-score threshold in `SyncController` from **5 → 1** so the foreground service starts as soon as any job is queued.
* Removed compile-time dependency between `data` and `mail` modules by invoking the service via component name only.
* Added automatic start of the foreground service when the app backgrounds and pending work exists (`SyncLifecycleObserver`).
* Added `POST_NOTIFICATIONS` permission to `mail` manifest and changed `MainViewModel`'s permission-request flow to use a `MutableSharedFlow` with `replay = 1` to guarantee the dialog appears.
* Build verified with `./gradlew assembleDebug` (no warnings except deprecations). 