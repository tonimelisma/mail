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
    version = 3,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // This migration adds the 'messages' table, assuming it didn't exist in version 1.
                // Room's @Entity definition for MessageEntity will guide the expected schema.
                // We also need to create indices that were specified in MessageEntity.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (" +
                            "`messageId` TEXT NOT NULL, " +
                            "`accountId` TEXT NOT NULL, " +
                            "`folderId` TEXT NOT NULL, " +
                            "`subject` TEXT, " +
                            "`senderAddress` TEXT, " +
                            "`senderName` TEXT, " +
                            "`recipientAddresses` TEXT, " +
                            "`ccAddresses` TEXT, " +
                            "`bccAddresses` TEXT, " +
                            "`recipientNames` TEXT, " +
                            "`snippet` TEXT, " +
                            "`bodyPreview` TEXT, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`isRead` INTEGER NOT NULL, " +
                            "`isStarred` INTEGER NOT NULL, " +
                            "`hasAttachments` INTEGER NOT NULL, " +
                            "`sentDateTime` TEXT, " +
                            "`receivedDateTime` TEXT, " +
                            "PRIMARY KEY(`messageId`), " +
                            "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                // Add indices as defined in MessageEntity (example)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_accountId_folderId_timestamp` ON `messages` (`accountId`, `folderId`, `timestamp` DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_messageId_accountId` ON `messages` (`messageId`, `accountId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `needsSync` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
} 