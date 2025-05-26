package net.melisma.core_db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.melisma.core_db.converter.WellKnownFolderTypeConverter
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.core_db.entity.FolderEntity

@Database(
    entities = [AccountEntity::class, FolderEntity::class],
    version = 1,
    exportSchema = false // Set to true for production apps for schema migration history
)
@TypeConverters(WellKnownFolderTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao

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
                    // .fallbackToDestructiveMigration() // Use with caution during development
                    // .addMigrations(MIGRATION_1_2) // Example for future migrations
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Example Migration (if needed in the future)
        /*
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // db.execSQL("ALTER TABLE folders ADD COLUMN new_column TEXT")
            }
        }
        */
    }
} 