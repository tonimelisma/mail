package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.WellKnownFolderType

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE // If an account is deleted, its folders are also deleted
        )
    ],
    indices = [Index(value = ["accountId"])]
)
data class FolderEntity(
    @PrimaryKey val id: String, // Corresponds to MailFolder.id
    val accountId: String,
    val displayName: String,
    val totalItemCount: Int,
    val unreadItemCount: Int,
    val type: WellKnownFolderType // Room will use TypeConverter for this
) 