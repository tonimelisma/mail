package net.melisma.core_db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.AutoMigration
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
import net.melisma.core_db.dao.MessageFolderJunctionDao
import net.melisma.core_db.dao.FolderSyncStateDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.FolderEntity
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_db.entity.MessageEntity
import net.melisma.core_db.entity.PendingActionEntity
import net.melisma.core_db.entity.MessageFolderJunction
import net.melisma.core_db.entity.FolderSyncStateEntity
import net.melisma.core_db.migration.M18_M19

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        MessageBodyEntity::class,
        AttachmentEntity::class,
        PendingActionEntity::class,
        MessageFolderJunction::class,
        FolderSyncStateEntity::class
    ],
    version = 20,
    exportSchema = true,
    // autoMigrations disabled during ongoing schema development – rely on fallbackToDestructiveMigration in AppDatabase builder.
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
        val M18_19 = M18_M19()
        val M19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN latestDeltaToken TEXT")
                db.execSQL("ALTER TABLE folders ADD COLUMN isPlaceholder INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}