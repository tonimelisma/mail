package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import net.melisma.core_db.converter.PayloadConverter // Will create this next
import net.melisma.core_db.model.PendingActionStatus

@Entity(tableName = "pending_actions")
@TypeConverters(PayloadConverter::class)
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: String,
    val actionType: String, // e.g., ActionUploadWorker.ACTION_MARK_AS_READ
    val entityId: String,   // e.g., messageId or threadId
    val payload: Map<String, String?>, // Stored as JSON string via TypeConverter
    var status: PendingActionStatus = PendingActionStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAttemptAt: Long? = null,
    var attemptCount: Int = 0,
    val maxAttempts: Int = 5, // Default max attempts
    var lastError: String? = null
) 