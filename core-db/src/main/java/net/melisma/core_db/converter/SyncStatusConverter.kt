package net.melisma.core_db.converter

import androidx.room.TypeConverter
import net.melisma.core_data.model.SyncStatus

class SyncStatusConverter {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toSyncStatus(statusString: String?): SyncStatus? {
        return statusString?.let { enumValueOf<SyncStatus>(it) }
    }
} 