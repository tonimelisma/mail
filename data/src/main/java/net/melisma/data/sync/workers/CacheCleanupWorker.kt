package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_data.preferences.UserPreferencesRepository
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import net.melisma.core_db.dao.PendingActionDao
import net.melisma.core_db.entity.MessageEntity
import net.melisma.core_db.model.PendingActionStatus

@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val messageBodyDao: MessageBodyDao,
    private val attachmentDao: AttachmentDao,
    private val appDatabase: AppDatabase, // For transaction (though not strictly used in this version's loop)
    private val userPreferencesRepository: UserPreferencesRepository, // Inject UserPreferencesRepository
    private val pendingActionDao: PendingActionDao // Added PendingActionDao
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "CacheCleanupWorker"

    companion object {
        // Default to 500 MB
        private val DEFAULT_CACHE_SIZE_LIMIT_BYTES = 500L * 1024L * 1024L 
        // Target 80% of the limit after cleanup
        private const val TARGET_CACHE_PERCENTAGE = 0.80 
        // 90 days in milliseconds
        private val MAX_AGE_FOR_RECENT_ACCESS_MS = TimeUnit.DAYS.toMillis(90)
        // Messages older than this (overall timestamp) are candidates for header eviction if other parts are gone
        private val MIN_AGE_FOR_MESSAGE_TIMESTAMP_EVICTION_MS = TimeUnit.DAYS.toMillis(90)
    }

    private suspend fun getCurrentCacheSizeBytes(): Long {
        val attachmentTotalSize = attachmentDao.getAllDownloadedAttachments().sumOf { it.size }
        // Fetch all message bodies and sum their sizes
        val messageBodyTotalSize = messageBodyDao.getAllMessageBodies().sumOf { it.sizeInBytes } // Assuming a getAllMessageBodies() method
        Timber.tag(TAG).d("Current cache: Attachments = $attachmentTotalSize bytes, Bodies = $messageBodyTotalSize bytes")
        return attachmentTotalSize + messageBodyTotalSize
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("Starting cache cleanup work.")

        try {
            var currentCacheSizeBytes = getCurrentCacheSizeBytes()
            val userPrefs = userPreferencesRepository.userPreferencesFlow.first()
            val cacheLimitBytes = userPrefs.cacheSizeLimitBytes
            val targetCacheSizeBytes = (cacheLimitBytes * TARGET_CACHE_PERCENTAGE).toLong()

            Timber.tag(TAG).d("Current cache size: $currentCacheSizeBytes bytes. Limit: $cacheLimitBytes bytes (from prefs). Target: $targetCacheSizeBytes bytes.")

            if (currentCacheSizeBytes <= targetCacheSizeBytes) {
                Timber.tag(TAG).d("Cache size is within target. No cleanup needed.")
                return@withContext Result.success()
            }

            Timber.tag(TAG).d("Cache size ($currentCacheSizeBytes) exceeds target ($targetCacheSizeBytes). Starting eviction...")

            val now = System.currentTimeMillis()
            val maxLastAccessedTimestampForExclusion = now - MAX_AGE_FOR_RECENT_ACCESS_MS
            val messageOlderThanTimestampThreshold = now - MIN_AGE_FOR_MESSAGE_TIMESTAMP_EVICTION_MS

            val excludedSyncStates = listOf(
                SyncStatus.PENDING_UPLOAD.name,
                SyncStatus.PENDING_DOWNLOAD.name
            )

            // Fetch all potentially evictable messages initially
            val allMessagesConsidered = messageDao.getCacheEvictionCandidates(
                maxLastAccessedTimestampMillis = maxLastAccessedTimestampForExclusion, // This effectively filters out messages accessed within 90 days
                excludedSyncStates = excludedSyncStates
            )
            Timber.tag(TAG).d("Found ${allMessagesConsidered.size} messages as initial candidates (accessed > 90 days ago or never, not draft/outbox, not in excluded sync states).")


            // Fetch active pending action entity IDs to exclude them
            val activePendingActionEntityIds = pendingActionDao.getActiveActionEntityIds(
                listOf(PendingActionStatus.PENDING, PendingActionStatus.RETRY)
            ).toSet()

            val filteredCandidates = allMessagesConsidered.filterNot { message ->
                val isPendingAction = activePendingActionEntityIds.contains(message.id)
                if (isPendingAction) {
                    Timber.tag(TAG).d("Excluding message ${message.id} from eviction due to pending action.")
                }
                isPendingAction
            }
            Timber.tag(TAG).d("Found ${filteredCandidates.size} messages after excluding those with active pending actions.")


            // Separate candidates into tiers
            val tier1Candidates = filteredCandidates
                .filter { it.timestamp < messageOlderThanTimestampThreshold } // Message itself is older than 90 days
                .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.lastAccessedTimestamp ?: Long.MAX_VALUE }) // Oldest message first, then by oldest access
            Timber.tag(TAG).d("Tier 1 Candidates (message older than 90 days): ${tier1Candidates.size}")


            val tier2Candidates = filteredCandidates
                .filter { it.timestamp >= messageOlderThanTimestampThreshold } // Message itself is NOT older than 90 days
                // Implicitly, these were not accessed in last 90 days due to getCacheEvictionCandidates query using maxLastAccessedTimestampForExclusion
                .sortedWith(compareBy<MessageEntity> { it.lastAccessedTimestamp ?: Long.MAX_VALUE }.thenBy { it.timestamp }) // Least recently accessed first, then by oldest message
            Timber.tag(TAG).d("Tier 2 Candidates (message not older than 90 days, but unaccessed > 90 days): ${tier2Candidates.size}")

            // Process Tier 1
            if (currentCacheSizeBytes > targetCacheSizeBytes) {
                Timber.tag(TAG).i("Processing Tier 1 candidates...")
                currentCacheSizeBytes = processEvictionTier(tier1Candidates, currentCacheSizeBytes, targetCacheSizeBytes, "Tier 1", true)
            }

            // Process Tier 2 if needed
            if (currentCacheSizeBytes > targetCacheSizeBytes) {
                Timber.tag(TAG).i("Processing Tier 2 candidates...")
                currentCacheSizeBytes = processEvictionTier(tier2Candidates, currentCacheSizeBytes, targetCacheSizeBytes, "Tier 2", false)
            }

            Timber.tag(TAG).d("Cache cleanup finished. Final cache size: $currentCacheSizeBytes bytes.")
            Result.success()

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during cache cleanup.")
            Result.failure()
        }
    }

    private suspend fun processEvictionTier(
        candidates: List<MessageEntity>,
        initialCacheSize: Long,
        targetCacheSize: Long,
        tierName: String,
        isMessageIntrinsicallyOldTier: Boolean // True if this tier is for messages older than 90 days by their own timestamp
    ): Long {
        var currentCacheSizeBytes = initialCacheSize
        val now = System.currentTimeMillis() // Needed for header eviction check if not Tier 1

        for (message in candidates) {
            if (currentCacheSizeBytes <= targetCacheSize) {
                Timber.tag(TAG).d("Cache size reached target during $tierName eviction. Stopping tier.")
                break
            }
            Timber.tag(TAG).d("$tierName: Processing message for eviction: ${message.id} (Subject: ${message.subject ?: "N/A"}, Timestamp: ${message.timestamp}, LastAccess: ${message.lastAccessedTimestamp})")

            // 1. Evict Attachments
            val downloadedAttachments = attachmentDao.getDownloadedAttachmentsForMessage(message.id)
            if (downloadedAttachments.isNotEmpty()) {
                Timber.tag(TAG).d("$tierName: Message ${message.id} has ${downloadedAttachments.size} downloaded attachments. Attempting to evict.")
                for (attachment in downloadedAttachments) {
                    if (attachment.localFilePath != null) {
                        try {
                            val file = File(attachment.localFilePath!!)
                            if (file.exists()) {
                                val fileSize = file.length()
                                if (file.delete()) {
                                    Timber.tag(TAG).i("$tierName: Deleted attachment file: ${attachment.localFilePath} (Size: $fileSize) for msg ${message.id}")
                                    attachmentDao.resetDownloadStatus(attachment.attachmentId, SyncStatus.IDLE.name)
                                    currentCacheSizeBytes -= fileSize
                                    Timber.tag(TAG).d("$tierName: Cache size after deleting attachment ${attachment.attachmentId}: $currentCacheSizeBytes bytes.")
                                } else {
                                    Timber.tag(TAG).w("$tierName: Failed to delete attachment file: ${attachment.localFilePath} for msg ${message.id}")
                                }
                            } else {
                                Timber.tag(TAG).w("$tierName: Attachment file not found at path: ${attachment.localFilePath} for attachment ${attachment.attachmentId}, msg ${message.id}. Resetting DB status.")
                                attachmentDao.resetDownloadStatus(attachment.attachmentId, SyncStatus.IDLE.name)
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "$tierName: Error deleting attachment file: ${attachment.localFilePath} for msg ${message.id}")
                        }
                    }
                    if (currentCacheSizeBytes <= targetCacheSize) break // Check after each attachment
                }
            }
            if (currentCacheSizeBytes <= targetCacheSize) {
                Timber.tag(TAG).d("$tierName: Cache size reached target after attachment eviction for message ${message.id}. Continuing to next message or stopping tier.")
                // If target met, outer loop will break. If not, we continue with this message's body.
            }

            // 2. Evict Message Body (if target not yet met for this message's items)
            if (currentCacheSizeBytes > targetCacheSize) {
                val bodyEntity = messageBodyDao.getBodyForMessage(message.id)
                if (bodyEntity != null && bodyEntity.content != null) { // Check if body exists and has content
                    val bodySize = bodyEntity.sizeInBytes
                    if (bodySize > 0) { // Ensure there's actual size to reclaim
                        val bodyDeletedCount = messageBodyDao.deleteMessageBody(message.id)
                        if (bodyDeletedCount > 0) {
                            currentCacheSizeBytes -= bodySize
                            Timber.tag(TAG).d("$tierName: Deleted message body for message ${message.id}. Size: $bodySize. New cache size: $currentCacheSizeBytes")
                        } else {
                            Timber.tag(TAG).w("$tierName: Attempted to delete body for message ${message.id}, but DB operation returned 0 (already deleted or race?).")
                        }
                    } else {
                         Timber.tag(TAG).d("$tierName: Message body for message ${message.id} has size 0 or content is null, no size to reclaim from body itself.")
                    }
                } else {
                    Timber.tag(TAG).d("$tierName: No message body content to evict for message ${message.id}.")
                }
            }


            // 3. Evict Message Header (if target not yet met for this message's items)
            if (currentCacheSizeBytes > targetCacheSize) {
                // Check if attachments are all gone and body is gone
                val attachmentsRemaining = attachmentDao.getDownloadedAttachmentsForMessage(message.id).isNotEmpty()
                val bodyRemaining = messageBodyDao.getBodyForMessage(message.id)?.content != null

                var canEvictHeader = false
                if (isMessageIntrinsicallyOldTier) { // Tier 1: message.timestamp is older than 90 days
                    canEvictHeader = true // Condition met by being in this tier.
                     Timber.tag(TAG).d("$tierName: Message ${message.id} is in intrinsically old tier.")
                } else { // Tier 2: message.timestamp is NOT older than 90 days, but unaccessed
                    // For Tier 2, ARCHITECTURE.MD allows deleting "data" which includes headers if attachments/body are gone.
                    canEvictHeader = true
                    Timber.tag(TAG).d("$tierName: Message ${message.id} is in unaccessed recent tier.")
                }

                if (canEvictHeader && !attachmentsRemaining && !bodyRemaining) {
                    Timber.tag(TAG).d("$tierName: Message ${message.id} has no downloaded content. Evicting header.")
                    val headerDeletedCount = messageDao.deleteMessageById(message.id)
                    if (headerDeletedCount > 0) {
                        Timber.tag(TAG).d("$tierName: Deleted message header for ${message.id}.")
                        // Note: Size of header is not explicitly tracked/subtracted from cache size.
                        // The primary cache contributors are attachments and bodies.
                    } else {
                        Timber.tag(TAG).w("$tierName: Attempted to delete header for message ${message.id}, but DB operation returned 0 (already deleted or race?).")
                    }
                } else {
                    Timber.tag(TAG).d("$tierName: Conditions not met for header eviction for message ${message.id} (attachmentsRemaining: $attachmentsRemaining, bodyRemaining: $bodyRemaining, canEvictHeaderBasedOnTier: $canEvictHeader).")
                }
            }
        }
        return currentCacheSizeBytes
    }
}
