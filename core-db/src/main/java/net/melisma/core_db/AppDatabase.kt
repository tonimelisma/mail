package net.melisma.core_db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.converter.StringListConverter
import net.melisma.core_db.converter.WellKnownFolderTypeConverter
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.RemoteKeyDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.FolderEntity
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_db.entity.MessageEntity
import net.melisma.core_db.entity.RemoteKeyEntity

class SyncStatusConverter {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toSyncStatus(status: String?): SyncStatus? {
        return status?.let { SyncStatus.valueOf(it) }
    }
}

@Database(
    entities = [
        AccountEntity::class, 
        FolderEntity::class, 
        MessageEntity::class, 
        MessageBodyEntity::class,
        AttachmentEntity::class,
        RemoteKeyEntity::class
    ],
    version = 11,
    exportSchema = false // Set to true for production apps for schema migration history
)
@TypeConverters(WellKnownFolderTypeConverter::class, StringListConverter::class, SyncStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun messageBodyDao(): MessageBodyDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun remoteKeyDao(): RemoteKeyDao

    companion object {
        // This companion object is now empty, as database creation is handled by Hilt.
    }
}