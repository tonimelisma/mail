package net.melisma.core_db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migration from version 18 to 19.
 *
 * At the moment the previous release marked v18 as an intermediary schema with no
 * production deployments, therefore we can safely implement a no-op migration
 * that preserves existing data.
 *
 * If you later add changes between v18→19, update the SQL statements below and
 * increment the database version accordingly.
 */
class M18_M19 : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No schema changes between 18 and 19 – placeholder migration to unblock build.
        // NOTE: Replace with real migration SQL if the schema diverges in the future.
    }
} 