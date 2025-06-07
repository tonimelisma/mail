package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.PendingActionEntity
import net.melisma.core_db.model.PendingActionStatus

@Dao
interface PendingActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAction(action: PendingActionEntity): Long

    @Update
    suspend fun updateAction(action: PendingActionEntity)

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun getActionById(id: Long): PendingActionEntity?

    // Get the next action to process: PENDING or RETRY, not exceeding max attempts, ordered by creation time.
    @Query("SELECT * FROM pending_actions WHERE (status = :pendingStatus OR status = :retryStatus) AND attemptCount < maxAttempts ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextActionToProcess(pendingStatus: PendingActionStatus = PendingActionStatus.PENDING, retryStatus: PendingActionStatus = PendingActionStatus.RETRY): PendingActionEntity?

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun deleteActionById(id: Long)

    @Query("DELETE FROM pending_actions WHERE accountId = :accountId")
    suspend fun deleteActionsForAccount(accountId: String)

    @Query("SELECT * FROM pending_actions WHERE status = :failedStatus ORDER BY createdAt DESC")
    fun getFailedActions(failedStatus: PendingActionStatus = PendingActionStatus.FAILED): Flow<List<PendingActionEntity>>

    @Query("SELECT * FROM pending_actions WHERE accountId = :accountId AND entityId = :entityId AND actionType = :actionType AND (status = :pendingStatus OR status = :retryStatus) ORDER BY createdAt DESC")
    suspend fun findSimilarPendingAction(accountId: String, entityId: String, actionType: String, pendingStatus: PendingActionStatus = PendingActionStatus.PENDING, retryStatus: PendingActionStatus = PendingActionStatus.RETRY): PendingActionEntity?

} 