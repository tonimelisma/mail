package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.AppDatabase // Assuming direct AppDatabase access for multiple DAOs
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import timber.log.Timber

@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val messageBodyDao: MessageBodyDao,
    private val attachmentDao: AttachmentDao,
    private val appDatabase: AppDatabase // For transaction
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "CacheCleanupWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Timber.d("$TAG: Starting cache cleanup work.")

        try {
            // TODO: P3_CACHE - Define actual age threshold (e.g., from settings).
            val ageThresholdDays = 30L
            val maxTimestamp = System.currentTimeMillis() - (ageThresholdDays * 24 * 60 * 60 * 1000L)

            // Get messages older than the threshold, excluding those pending upload or drafts/outbox.
            val messagesToDelete = messageDao.getMessagesOlderThan(maxTimestamp, SyncStatus.PENDING_UPLOAD)

            if (messagesToDelete.isEmpty()) {
                Timber.d("$TAG: No messages found older than $ageThresholdDays days to cleanup.")
                return@withContext Result.success()
            }

            Timber.d("$TAG: Found ${messagesToDelete.size} messages older than $ageThresholdDays days that are candidates for deletion.")

            // TODO: P3_CACHE - Consider also deleting associated MessageBodyEntity and AttachmentEntity records for the deleted messages.
            // This should be done in a transaction.
            // For now, just log. In a real implementation, you would get IDs and delete related data.

            val messageIdsToDelete = messagesToDelete.map { it.messageId }

            // Placeholder: Actual deletion logic
            // appDatabase.withTransaction {
            //     attachmentDao.deleteAttachmentsForMessageIds(messageIdsToDelete)
            //     messageBodyDao.deleteMessageBodiesForMessageIds(messageIdsToDelete)
            //     messageDao.deleteMessagesByIds(messageIdsToDelete) // Requires new DAO method: deleteMessagesByIds(List<String>)
            // }
            // Timber.i("$TAG: Successfully deleted ${messageIdsToDelete.size} old messages and their related data.")

            Timber.i("$TAG: (Placeholder) Would delete ${messagesToDelete.size} messages older than $ageThresholdDays days.")
            // For now, we are just logging. To actually delete, uncomment and implement DAO methods.

            Timber.d("$TAG: Cache cleanup work finished successfully.")
            Result.success()

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during cache cleanup.")
            Result.failure()
        }
    }
}
