package net.melisma.core_db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.melisma.core_db.converter.StringListConverter
import net.melisma.core_db.converter.WellKnownFolderTypeConverter
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.core_db.entity.FolderEntity
import net.melisma.core_db.entity.MessageEntity

@Database(
    entities = [AccountEntity::class, FolderEntity::class, MessageEntity::class],
    version = 4,
    exportSchema = false // Set to true for production apps for schema migration history
)
@TypeConverters(WellKnownFolderTypeConverter::class, StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
    }
} 