package net.melisma.core_db.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.PendingActionDao
import javax.inject.Singleton
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.melisma.core_db.dao.MessageFolderJunctionDao
import net.melisma.core_db.dao.FolderSyncStateDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // Define the migration from version 14 to 15
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isOutbox INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        Log.d("DatabaseModule", "Providing AppDatabase instance.")
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "melisma.db"
        )
            .addMigrations(AppDatabase.M18_19, AppDatabase.M19_20, AppDatabase.M21_22, AppDatabase.M22_23)
            // Destructive migration is enabled for development builds to speed up schema iteration.
            // In a production app, every schema change would require a tested Migration plan.
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideAccountDao(appDatabase: AppDatabase): AccountDao {
        return appDatabase.accountDao()
    }

    @Provides
    @Singleton
    fun provideFolderDao(appDatabase: AppDatabase): FolderDao {
        return appDatabase.folderDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(appDatabase: AppDatabase): MessageDao {
        return appDatabase.messageDao()
    }

    @Provides
    @Singleton
    fun provideMessageBodyDao(appDatabase: AppDatabase): MessageBodyDao {
        return appDatabase.messageBodyDao()
    }

    @Provides
    @Singleton
    fun provideAttachmentDao(appDatabase: AppDatabase): AttachmentDao {
        return appDatabase.attachmentDao()
    }

    @Provides
    @Singleton
    fun providePendingActionDao(appDatabase: AppDatabase): PendingActionDao {
        return appDatabase.pendingActionDao()
    }

    @Provides
    @Singleton
    fun provideMessageFolderJunctionDao(appDatabase: AppDatabase): MessageFolderJunctionDao {
        return appDatabase.messageFolderJunctionDao()
    }

    @Provides
    @Singleton
    fun provideFolderSyncStateDao(appDatabase: AppDatabase): FolderSyncStateDao {
        return appDatabase.folderSyncStateDao()
    }
} 