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
    version = 23,
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
        val M18_19 = M18_M19()
        val M19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN latestDeltaToken TEXT")
                db.execSQL("ALTER TABLE folders ADD COLUMN isPlaceholder INTEGER NOT NULL DEFAULT 0")
            }
        }
        val M21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_sync_state RENAME TO folder_sync_state_old")
                db.execSQL("CREATE TABLE folder_sync_state (folderId TEXT NOT NULL, nextPageToken TEXT, lastSyncedTimestamp INTEGER, deltaToken TEXT, historyId TEXT, continuousHistoryToTimestamp INTEGER, PRIMARY KEY(folderId))")
                db.execSQL("INSERT INTO folder_sync_state (folderId, nextPageToken, lastSyncedTimestamp, deltaToken, historyId) SELECT folderId, nextPageToken, lastSyncedTimestamp, deltaToken, historyId FROM folder_sync_state_old")
                db.execSQL("DROP TABLE folder_sync_state_old")
            }
        }
        val M22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN hasFullBodyCached INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}