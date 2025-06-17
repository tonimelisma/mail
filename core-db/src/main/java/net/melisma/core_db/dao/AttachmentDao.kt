package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.entity.AttachmentEntity

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY fileName ASC")
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY fileName ASC")
    suspend fun getAttachmentsForMessageSuspend(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    fun getAttachmentById(id: Long): Flow<AttachmentEntity?>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getAttachmentByIdSuspend(id: Long): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Update
    suspend fun updateAttachment(attachment: AttachmentEntity)

    @Query("UPDATE attachments SET syncStatus = :syncStatus, lastSyncError = :error WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, syncStatus: EntitySyncStatus, error: String?)

    @Query("""
        UPDATE attachments 
        SET isDownloaded = 1, 
            localFilePath = :localPath, 
            downloadTimestamp = :downloadTimestamp,
            syncStatus = 'SYNCED',
            lastSyncError = NULL
        WHERE id = :id
    """)
    suspend fun updateDownloadSuccess(id: Long, localPath: String, downloadTimestamp: Long)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Query("SELECT COUNT(*) FROM attachments WHERE messageId = :messageId")
    suspend fun getAttachmentCountForMessage(messageId: String): Int

    @Query("SELECT * FROM attachments WHERE isDownloaded = 1 AND localFilePath IS NOT NULL")
    suspend fun getAllDownloadedAttachments(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND isDownloaded = 1 AND localFilePath IS NOT NULL")
    suspend fun getDownloadedAttachmentsForMessage(messageId: String): List<AttachmentEntity>

    @Query("UPDATE attachments SET isDownloaded = 0, localFilePath = NULL, downloadTimestamp = NULL, syncStatus = :newSyncStatus, lastSyncError = NULL WHERE id = :id")
    suspend fun resetDownloadStatus(id: Long, newSyncStatus: EntitySyncStatus)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAttachments(attachments: List<AttachmentEntity>)

    @Query("SELECT SUM(size) FROM attachments WHERE accountId = :accountId AND isDownloaded = 1")
    suspend fun getTotalDownloadedSizeForAccount(accountId: String): Long?

    @Query("SELECT SUM(size) FROM attachments WHERE isDownloaded = 1")
    suspend fun getTotalDownloadedSize(): Long?

    @Query("""
        SELECT * FROM attachments
        WHERE accountId = :accountId
          AND isDownloaded = 0
        LIMIT :limit
    """)
    suspend fun getUndownloadedAttachmentsForAccount(accountId: String, limit: Int = 50): List<AttachmentEntity>
}