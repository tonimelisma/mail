package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.AttachmentEntity

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY fileName ASC")
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY fileName ASC")
    suspend fun getAttachmentsForMessageSuspend(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE attachmentId = :attachmentId")
    fun getAttachmentById(attachmentId: String): Flow<AttachmentEntity?>

    @Query("SELECT * FROM attachments WHERE attachmentId = :attachmentId")
    suspend fun getAttachmentByIdSuspend(attachmentId: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Update
    suspend fun updateAttachment(attachment: AttachmentEntity)

    @Query(
        """
        UPDATE attachments 
        SET isDownloaded = :isDownloaded, 
            localFilePath = :localFilePath, 
            downloadTimestamp = :timestamp,
            lastSyncError = :error
        WHERE attachmentId = :attachmentId
    """
    )
    suspend fun updateDownloadStatus(
        attachmentId: String,
        isDownloaded: Boolean,
        localFilePath: String?,
        timestamp: Long?,
        error: String? = null
    )

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String)

    @Query("DELETE FROM attachments WHERE attachmentId = :attachmentId")
    suspend fun deleteAttachment(attachmentId: String)

    @Query("SELECT COUNT(*) FROM attachments WHERE messageId = :messageId")
    suspend fun getAttachmentCountForMessage(messageId: String): Int

    @Query("SELECT * FROM attachments WHERE isDownloaded = 1 AND localFilePath IS NOT NULL")
    suspend fun getAllDownloadedAttachments(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND isDownloaded = 1 AND localFilePath IS NOT NULL")
    suspend fun getDownloadedAttachmentsForMessage(messageId: String): List<AttachmentEntity>

    @Query("UPDATE attachments SET isDownloaded = 0, localFilePath = NULL, downloadTimestamp = NULL, syncStatus = :newSyncStatus, lastSyncError = NULL WHERE attachmentId = :attachmentId")
    suspend fun resetDownloadStatus(attachmentId: String, newSyncStatus: String)
}