package net.melisma.core_db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.converter.StringListConverter
import net.melisma.core_db.converter.WellKnownFolderTypeConverter
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.FolderEntity
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_db.entity.MessageEntity

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
        AttachmentEntity::class
    ],
    version = 9,
    exportSchema = false // Set to true for production apps for schema migration history
)
@TypeConverters(WellKnownFolderTypeConverter::class, StringListConverter::class, SyncStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun messageBodyDao(): MessageBodyDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DATABASE_NAME = "melisma_mail.db"

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // This migration adds the 'messages' table.
                // Schema based on MessageEntity.kt as of DB version 4,
                // excluding 'needsSync' which is handled by MIGRATION_2_3.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (" +
                            "`messageId` TEXT NOT NULL, " +
                            "`accountId` TEXT NOT NULL, " +
                            "`folderId` TEXT NOT NULL, " +
                            "`threadId` TEXT, " +
                            "`subject` TEXT, " +
                            "`snippet` TEXT, " +
                            "`senderName` TEXT, " +
                            "`senderAddress` TEXT, " +
                            "`recipientNames` TEXT, " +
                            "`recipientAddresses` TEXT, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`sentTimestamp` INTEGER, " +
                            "`isRead` INTEGER NOT NULL, " +
                            "`isStarred` INTEGER NOT NULL, " +
                            "`hasAttachments` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`messageId`), " +
                            "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                // Remove old indices
                db.execSQL("DROP INDEX IF EXISTS `index_messages_accountId_folderId_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_messages_messageId_accountId`")

                // Add indices as defined in MessageEntity
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_accountId` ON `messages` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_folderId` ON `messages` (`folderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_threadId` ON `messages` (`threadId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `needsSync` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `needsReauthentication` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_bodies` (" +
                            "`message_id` TEXT NOT NULL, " +
                            "`content_type` TEXT NOT NULL DEFAULT 'TEXT', " +
                            "`content` TEXT, " +
                            "`last_fetched_ts` INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY(`message_id`), " +
                            "FOREIGN KEY(`message_id`) REFERENCES `messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_message_bodies_message_id` ON `message_bodies` (`message_id`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `isLocallyDeleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns to messages table for draft/outbox support
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `isDraft` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `isOutbox` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `draftType` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `draftParentId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `sendAttempts` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `lastSendError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `scheduledSendTime` INTEGER DEFAULT NULL")

                // Create attachments table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachments` (
                        `attachmentId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `contentId` TEXT,
                        `isInline` INTEGER NOT NULL DEFAULT 0,
                        `isDownloaded` INTEGER NOT NULL DEFAULT 0,
                        `localFilePath` TEXT,
                        `downloadTimestamp` INTEGER,
                        `downloadError` TEXT,
                        PRIMARY KEY(`attachmentId`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """
                )

                // Create indices for attachments table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_messageId` ON `attachments` (`messageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_attachmentId` ON `attachments` (`attachmentId`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add to accounts table
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT \'\'\'IDLE\'\'\'")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `lastSyncAttemptTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `lastSuccessfulSyncTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `isLocalOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `needsFullSync` INTEGER NOT NULL DEFAULT 0")

                // Add to folders table
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT \'\'\'IDLE\'\'\'")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `lastSyncAttemptTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `lastSuccessfulSyncTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `isLocalOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `needsFullSync` INTEGER NOT NULL DEFAULT 0")

                // Add to messages table
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT \'\'\'IDLE\'\'\'")
                db.execSQL("UPDATE `messages` SET `syncStatus` = \'\'\'PENDING_DOWNLOAD\'\'\' WHERE `needsSync` = 1")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `lastSyncAttemptTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `lastSuccessfulSyncTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `isLocalOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `needsFullSync` INTEGER NOT NULL DEFAULT 0")

                // Add to message_bodies table
                db.execSQL("ALTER TABLE `message_bodies` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT \'\'\'IDLE\'\'\'")
                db.execSQL("ALTER TABLE `message_bodies` ADD COLUMN `lastSyncAttemptTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `message_bodies` ADD COLUMN `lastSuccessfulSyncTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `message_bodies` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `message_bodies` ADD COLUMN `isLocalOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `message_bodies` ADD COLUMN `needsFullSync` INTEGER NOT NULL DEFAULT 0")

                // Add to attachments table
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT \'\'\'IDLE\'\'\'")
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `lastSyncAttemptTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `lastSuccessfulSyncTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `isLocalOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `needsFullSync` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
} 