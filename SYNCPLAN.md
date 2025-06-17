### **Architecture A Migration Plan – Phase 2: Vision Completion**

This document outlines the remaining work to fully implement the **SYNCVISION.md** specification. The core infrastructure, bug fixes, and basic job producers from Phase 1 are complete. This plan focuses on implementing the missing opportunistic features and hardening the existing logic.

---
## 📍 High-Level Roadmap
1.  **Implement Bulk Content Downloads:** Create a new `JobProducer` that intelligently queues `BulkFetchBodies` and `BulkFetchAttachments` jobs when the device state is favorable (e.g., unmetered network, not low on battery).
2.  **Harden Backfill Logic:** Refactor the backfill mechanism to be truly dynamic and responsive to changes in user preferences.
3.  **Final Polish:** Add comprehensive debug logging to the producer loop and clean up any remaining tech debt.

We will implement this in **two small PRs**.

---
## 1️⃣ PR-1 – Bulk Content Downloads (Opportunistic)

This PR implements the final missing piece of the core sync vision: proactively downloading content in the background to make the app feel faster and more responsive.

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `data/src/main/java/net/melisma/data/sync/BulkDownloadJobProducer.kt` (NEW) | Create a new `JobProducer` that implements the logic for opportunistic downloads. It should only run if the `NetworkGatekeeper` and `CachePressureGatekeeper` would allow the jobs. It will query for messages missing bodies and attachments pending download using existing DAO methods. | Low (new file) | Logic error might queue too many jobs; mitigate by limiting queries (e.g., `LIMIT 25`). |
| `data/src/main/java/net/melisma/data/di/DataModule.kt` | Add the new `BulkDownloadJobProducer` to the Hilt multibinding set for `JobProducer`. | Low | DI mis-wiring. |
| `SyncController.kt` | Add detailed `Timber.d` logging inside the `producerLoop` to make it clear which producer is running and how many jobs it creates. | Low | None. |

#### Implementation Steps
1. Create the new `BulkDownloadJobProducer.kt` file.
2. Inside its `produce()` method, query `messageDao.getMessagesMissingBody()` and `attachmentDao.getUndownloadedAttachmentsForAccount()`.
3. If results are found, create and return `SyncJob.BulkFetchBodies` and `SyncJob.BulkFetchAttachments` jobs.
4. Add the new producer to the `JobProducer` set in a Hilt module (e.g., `DataModule.kt`).
5. Add logging to the `producerLoop` in `SyncController.kt` to aid debugging.

---
## 2️⃣ PR-2 – Harden Dynamic Backfill & Final Polish

This PR improves the user experience by making historical sync fully responsive to settings changes and cleans up the last pieces of tech debt.

| File | Change | Blast-Radius | Risk |
|------|--------|-------------|------|
| `BackfillJobProducer.kt` | Modify the producer's logic. It should now track the *sync duration preference* it last ran with for each folder (e.g., in `FolderSyncStateEntity` or a separate table). If the current preference is greater than the last-used preference, it should trigger a new backfill, even if the previous one was "complete". | Medium | DB migration required if adding a column to `FolderSyncStateEntity`. Can be complex to get right. |
| `core-db/src/main/java/net/melisma/core_db/entity/FolderSyncStateEntity.kt` | Add a `backfillSyncDurationDays: Int?` column to store the setting used during the last backfill. | High | Requires a Room migration. |
| `core-db/src/main/java/net/melisma/core_db/AppDatabase.kt` | Implement the `M_XX_YY` migration to add the new column to the `folder_sync_state` table. | High | Risk of crashing users on upgrade if migration fails. Must be tested thoroughly. |
| `CHANGELOG.md`, `SYNCLOG.md` | Update documentation to reflect the final, completed state of the Sync Engine v2.1. | Low | Docs only. |

---
## ✅ Definition of Done
- Bulk downloads for bodies and attachments occur automatically in the background when network and cache conditions are suitable.
- Changing the "Sync mail for" setting from "30 days" to "90 days" correctly triggers a new backfill to download the missing 60 days of history.
- The `CHANGELOG.md` and `SYNCLOG.md` are updated to mark the v2.1 feature set as complete.
- The project builds successfully with `./gradlew build`. 