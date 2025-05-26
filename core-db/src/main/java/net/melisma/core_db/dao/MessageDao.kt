package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.MessageEntity

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND folderId = :folderId ORDER BY timestamp DESC")
    fun getMessagesForFolder(accountId: String, folderId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun deleteMessagesForFolder(accountId: String, folderId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAllMessagesForAccount(accountId: String)

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    fun getMessageById(messageId: String): Flow<MessageEntity?>

    // Helper to clear messages for a specific folder, then insert new ones (transactional)
    @androidx.room.Transaction
    suspend fun clearAndInsertMessagesForFolder(
        accountId: String,
        folderId: String,
        messages: List<MessageEntity>
    ) {
        deleteMessagesForFolder(accountId, folderId)
        insertOrUpdateMessages(messages)
    }
} 