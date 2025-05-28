package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.MessageBodyEntity

@Dao
interface MessageBodyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessageBody(messageBody: MessageBodyEntity)

    @Query("SELECT * FROM message_bodies WHERE message_id = :messageId")
    fun getMessageBody(messageId: String): Flow<MessageBodyEntity?>

    @Query("SELECT * FROM message_bodies WHERE message_id = :messageId")
    suspend fun getMessageBodyNonFlow(messageId: String): MessageBodyEntity? // For direct checks

    @Query("DELETE FROM message_bodies WHERE message_id = :messageId")
    suspend fun deleteMessageBody(messageId: String)

    // You might add queries to get bodies that haven't been fetched recently, etc.
    // For now, basic CRUD operations.
} 