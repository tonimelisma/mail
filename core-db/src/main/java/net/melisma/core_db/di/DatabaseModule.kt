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
import net.melisma.core_db.dao.RemoteKeyDao
import javax.inject.Singleton
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
            context,
            AppDatabase::class.java,
            "melisma_mail.db"
        )
            // Fallback for versions 1-13 to version 14 (or directly to 15 if preferred and data loss is ok for these old versions)
            // Since we have a specific 14->15 migration, we want to ensure any older version hits the fallback to get to 14 first,
            // or if the fallback can go directly to 15 safely.
            // For simplicity and matching previous behavior for 1-13:
            .fallbackToDestructiveMigration() // Phase-1: wipe & recreate schema for v16
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
    fun provideRemoteKeyDao(appDatabase: AppDatabase): RemoteKeyDao {
        return appDatabase.remoteKeyDao()
    }

    @Provides
    @Singleton
    fun providePendingActionDao(appDatabase: AppDatabase): PendingActionDao {
        return appDatabase.pendingActionDao()
    }
} 