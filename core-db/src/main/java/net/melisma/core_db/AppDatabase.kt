package net.melisma.core_db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.melisma.core_db.converter.StringListConverter
import net.melisma.core_db.converter.WellKnownFolderTypeConverter
import net.melisma.core_db.converter.PayloadConverter
import net.melisma.core_db.converter.EntitySyncStatusConverter
import net.melisma.core_db.converter.PendingActionStatusConverter
import net.melisma.core_db.converter.PendingActionStatusListConverter
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.PendingActionDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.FolderEntity
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_db.entity.MessageEntity
import net.melisma.core_db.entity.PendingActionEntity
import net.melisma.core_db.entity.MessageFolderJunction
import net.melisma.core_db.entity.FolderSyncStateEntity

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        MessageBodyEntity::class,
        AttachmentEntity::class,
        MessageFolderJunction::class,
        FolderSyncStateEntity::class,
        PendingActionEntity::class
    ],
    version = 18,
    exportSchema = true
)
@TypeConverters(
    WellKnownFolderTypeConverter::class, 
    StringListConverter::class, 
    EntitySyncStatusConverter::class,
    PayloadConverter::class,
    PendingActionStatusConverter::class,
    PendingActionStatusListConverter::class
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun messageBodyDao(): MessageBodyDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun messageFolderJunctionDao(): MessageFolderJunctionDao
    abstract fun folderSyncStateDao(): FolderSyncStateDao

    companion object {
        // This companion object is now empty, as database creation is handled by Hilt.
    }
}