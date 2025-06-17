# Connectivity-Throttling & Foreground-Service Reliability – Implementation Plan

_Last updated: 2025-06-17_

## 0. Problem Recap
* Foreground-service (FGS) hides its notification on Android 13+ when `POST_NOTIFICATIONS` permission is missing, so users cannot see that sync is protected.
* HeaderBackfill storms continue during partial network outages (DNS failures).  Re-queued jobs keep hitting the same error, generating noisy logs and keeping the process alive just long enough to be killed by the system.
* The new `ConnectivityHealthTracker` is not yet wired everywhere:
  * Only `BulkDownloadJobProducer` consults `isBlocked()`.
  * `BackfillJobProducer`, `CacheEvictionProducer`, and **SyncController.run()** still execute while BLOCKED.
  * Tracker thresholds are possibly too high (6 failures / 3 min) and never reach BLOCKED; we only stay in DEGRADED.
* Work-score threshold (5) is never reached because each HeaderBackfill is scored 1 ⇒ FGS is not resurrected after it self-stops.

---

## 1. High-Level Goals
1. Guarantee a visible, persistent notification whenever the app runs sync work in background.
2. Aggressively pause or down-throttle all non-critical jobs during DNS / transport failures to avoid spammy retries and background kills.
3. Provide clear logging and state transitions so QA can verify behaviour.

---

## 2. Task List (step-by-step)
### 2.1 Notification permission flow
| # | Task | File(s) | Notes |
|---|------|---------|-------|
| N-1 | Create `NotificationPermissionHelper` that checks & requests `POST_NOTIFICATIONS` via an activity-result contract | `mail/ui` | Show snackbar if denied permanently |
| N-2 | Call helper from `MailApplication.onCreate()` **only on Android 13+** | `MailApplication.kt` | Avoid prompting older SDKs |
| N-3 | Catch `Missing POST_NOTIFICATIONS…` warning in `InitialSyncForegroundService.updateNotification()` and escalate to helper via broadcast | `InitialSyncForegroundService.kt` |

### 2.2 Health-tracker integration
| # | Task | File(s) | Description |
|---|------|---------|-------------|
| H-1 | Lower `ConnectivityHealthTracker` window → **60 s**, `BLOCKED` threshold → **4 failures** | `ConnectivityHealthTracker.kt` |
| H-2 | Expose `shouldPauseHeavyWork(): Boolean` convenience | same file |
| H-3 | Wrap **all** JobProducers in a base class or individually guard: `BackfillJobProducer`, `CacheEvictionProducer` | each producer |
| H-4 | In `SyncController.run()` before processing a job:
```kotlin
if (connectivityHealthTracker.isBlocked() && job.priority < 80) {
    queue.put(job)  // push back
    delay(30_000)   // back-off
    continue
}
``` |
| H-5 | On `connectivityHealthTracker.state.collect` transition back to NORMAL, log "Network recovered – resuming queue" | `SyncController` |

### 2.3 Work-score tuning & FGS lifecycle
| # | Task | File(s) | Description |
|---|------|---------|-------------|
| W-1 | Raise `HeaderBackfill.workScore` → **3** (was 1) | `SyncJob.kt` |
| W-2 | Raise `CheckForNewMail.workScore` → **2** | same |
| W-3 | Keep threshold at 5 so two HeaderBackfills now trigger FGS |
| W-4 | Make FGS stop check wait for both (score == 0 **and** tracker == NORMAL) before self-stop | `InitialSyncForegroundService.kt` |

### 2.4 Retry/back-off policies
| # | Task | File(s) | Description |
| R-1 | When tracker is DEGRADED, extend SyncController's per-job delay after failure to `+15 s` | `SyncController` |
| R-2 | Implement exponential back-off for the **same folderId** in HeaderBackfill (store in memory HashMap<folderId,nextAllowedTs>) | `SyncController` or new helper |

### 2.5 Instrumentation & logging
| # | Task | File(s) | Description |
| L-1 | Add `Timber.tag("Throttle")` lines on every state change NORMAL↔DEGRADED↔BLOCKED | tracker |
| L-2 | Add log inside each producer when skipped due to throttle | producers |
| L-3 | Expose tracker state in `SyncController.status` so UI can surface banner later | `SyncControllerStatus` data-class |

### 2.6 Tests / validation
| # | Task | Description |
| T-1 | Unit test: inject fake clock → verify tracker transitions with failures & success |
| T-2 | Integration test: simulate DNS failure via `mockwebserver` & observe queue pause |
| T-3 | Manual QA script: deny notifications, background app, verify runtime permission prompt |

---

## 3. Soft Spots & Required Research
| Soft Spot | Why risk? | What to research / verify |
|-----------|----------|----------------------------|
| SP-1 Tracker thresholds | Might still under/over-react on real devices | Pull logs from various networks; profile failure rate when Wi-Fi toggles |
| SP-2 Notification permission UX | Permission fatigue; user may deny permanently | Check Material 3 guidelines; maybe show inline rationale before prompt |
| SP-3 Producer gating | Some future producer may forget to consult tracker | Consider abstract base class `ThrottleAwareProducer` |
| SP-4 Back-off logic | Re-queuing could starve higher-priority jobs | Measure queue size; maybe isolate per-account sub-queues |
| SP-5 Work-score math | Raising scores affects foreground threshold in other flows | Run bootstrap on 5 k messages and check FGS uptime |
| SP-6 SyncController status API | Adding tracker state may break existing UI observers | Grep for `.status.` usages and update tests |

### Research results
* Grep search confirms only `BulkDownloadJobProducer` depends on tracker – others need updates.
* `SyncControllerStatus` currently holds `isSyncing`, `networkAvailable`, etc.; no reactive UI field for throttle state – safe to extend.
* WorkScore mapping in `SyncJob.kt` shows HeaderBackfill=1, CheckForNewMail=1 → we will adjust.
* Permission helper absent; `MailApplication` is already Hilt-enabled, so we can inject and call.

---

## 4. Implementation sequencing
1. **Permission Helper** (N-tasks)
2. **Tracker tuning (H-1)** followed by integration tasks H-3, H-4.
3. **Work-score adjustments (W-tasks)**.
4. **Back-off logic & producer changes (R-tasks).**
5. Logging & status exposure (L-tasks).
6. Update docs (ARCHITECTURE.md & BACKLOG.md).
7. Build, test, commit.

---

## 5. Estimated Effort
* 1 day – Permission flow & UI polish
* 0.5 day – Tracker tuning & gating producers
* 0.5 day – SyncController pause + back-off
* 0.5 day – WorkScore tuning & FGS tweaks
* 0.5 day – Tests & documentation
* **Total:** ~3 working days for single developer 