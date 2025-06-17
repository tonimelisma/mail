### **Architecture A Migration Plan – Incremental Evolution of the existing SyncController**

This document converts the vision in **SYNCVISION.md** into an actionable, file-by-file to-do list that a junior developer can follow.  Each task states **where**, **what** and **why**, the **blast radius**, and any **gotchas / risks**.

---
## 📍 High-Level Roadmap
1. Add pluggable _Gatekeepers_ that can veto queuing when cache pressure / network / battery is outside allowed ranges.
2. Add a lightweight **WorkScore** metric to every `SyncJob` and expose `totalWorkScore` as a `StateFlow` from `SyncController`.
3. Teach the existing **InitialSyncForegroundService** to start/stop based on `totalWorkScore` instead of the coarse `isSyncing` flag.
4. Update Back-fill and Cache-Eviction logic to match the rules in **SYNCVISION.md** (90-day header window, 24-hour grace period, 98 % hard cache limit, 90 % soft limit).
5. Maintain backward compatibility for the UI – keep `isSyncing` (derived) so nothing crashes.
6. Write unit tests for the new Gatekeeper decisions & WorkScore maths.

We implement the above in **five small PRs** (you can squash before main merge).

---
## 1️⃣ PR-1  – Gatekeeper Infrastructure

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `data/src/main/java/net/melisma/data/sync/Gatekeeper.kt` (NEW) | Define an **interface** `Gatekeeper { suspend fun isAllowed(job: SyncJob): Boolean }`. | None (new file) | None.
| `data/src/main/java/net/melisma/data/sync/gate/CachePressureGatekeeper.kt` (NEW) | Implements interface. Reads `AttachmentDao`+`MessageBodyDao` sizes, compares to **90 %** of `UserPreferences.cacheSizeLimitBytes`. | Low | SQL cost – run on IO dispatcher.
| `SyncController.kt` | 1) Inject `List<Gatekeeper>`  (Hilt multibinding)  2) In `submit(job)` call `gatekeepers.all { it.isAllowed(job) }` before enqueue. | High – touches central queue. | Deadlock if `isAllowed` is long-running ⇒ ensure `withContext(Dispatchers.IO)`.
| `data/src/main/java/.../di/SyncGateModule.kt` (NEW) | Provide the gatekeeper impls via Hilt. | None | DI mis-wiring.

####  Implementation steps
1. Create interface + impls.  
2. Register with Hilt.  
3. Update `SyncController` constructor to accept `@JvmSuppressWildcards List<Gatekeeper>`.
4. Ensure `submit()` remains **non-suspending** (wrap check in `runBlocking` or keep gatekeepers fast).

---
## 2️⃣ PR-2  – WorkScore Metric

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `core-data/src/main/java/net/melisma/core_data/model/SyncJob.kt` | Add property `open val workScore: Int` to sealed class root.  Provide defaults in each subclass (e.g. DownloadBody = 2, Attachment = size/1 MB, Header = 1, FolderList = 3). | All job callers compile-error until updated. | Medium – keep serialisation constructors.
| `SyncController.kt` | Add private `_totalWorkScore` `MutableStateFlow<Int>` updated on `submit` (+ score) and after job finishes (– score).  Expose `totalWorkScore` as `asStateFlow()`. | High | Off-by-one on errors; ensure decrement in `finally` even on exception.
| Unit tests folder (NEW) | Add `SyncJobWorkScoreTest.kt`. | None | Build config for JUnit.

#### Steps
1. Update `SyncJob` root class signature.  
2. Touch every data class line – IntelliJ quick-fix.  
3. Update queue logic in `SyncController.run()` to subtract `job.workScore` in `finally`.

---
## 3️⃣ PR-3  – Foreground-Service Heuristics

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `InitialSyncForegroundService.kt` | Replace usage of `status.isSyncing` with `syncController.totalWorkScore` (> `WORK_THRESHOLD` = 10). | Medium – UI string only. | Service may flicker → add hysteresis of 5 seconds same as today.
| `SyncController.kt` | Derive `isSyncing = totalWorkScore.value > 0` for backwards compatibility. | Medium | None.

#### Steps
1. Add companion `WORK_THRESHOLD` = 10.  
2. Subscribe to `totalWorkScore` instead of `status.isSyncing`.

---
## 4️⃣ PR-4  – Back-fill & Eviction Alignment

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `BackfillJobProducer.kt` | Before queuing headers, call `gatekeeperCachePressure.isAllowed(job)`; skip if false. | Low | None.
| `CacheEvictionProducer.kt` | Change soft threshold to **98 %** for hard eviction trigger, keep 90 % as soft gate. | Low | Wrong constant.
| `SyncController.runCacheEviction()` | Modify selection predicate to `sentDate < now-90d AND (lastAccess == null || lastAccess < now-24h)`. | Medium | SQL perf – add new index on `lastAccessedTimestamp` (already exists).

---
## 5️⃣ PR-5  – UI & Compatibility Clean-up

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `mail/.../MessageDetailViewModel.kt` & others | Still rely on `isSyncing`. No change needed, flag now derived. | Low | None.
| Documentation | Update `ARCHITECTURE.md` diagram once Gatekeepers & WorkScore merged. | None | Docs only.

---
## 🛠️ General Risks & Mitigations
1. **Deadlocks** – gatekeeper check inside `submit()` must be quick; use non-blocking caches or snapshot sizes.
2. **WorkScore drift** – ensure decrement happens even if job throws; add unit test.
3. **Foreground Service flaps** – add hysteresis (≥ 5 s below threshold before stop).
4. **Schema migrations** – Changing eviction predicate needs no DB schema change, but if you later add new columns ensure `AppDatabase.migrations` updated.
5. **Junior dev confusion** – Keep each PR < 400 loc; write comments in new interfaces; run `./gradlew test` locally.

---
## ✅ Definition of Done
- All existing UI flows compile and behave unchanged.
- New unit tests in PR-2 pass.
- Foreground service starts only when `totalWorkScore ≥ 10` for >2 ticks and stops after 5 idle ticks.
- Back-fill pauses automatically when cache ≥ 90 % of limit.
- Cache eviction only runs when usage ≥ 98 % or at 24-h cadence. 