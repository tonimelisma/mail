package net.melisma.core_db.converter

import androidx.room.TypeConverter
import net.melisma.core_data.model.EntitySyncStatus

class EntitySyncStatusConverter {
    @TypeConverter
    fun fromSyncStatus(status: EntitySyncStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toSyncStatus(statusString: String?): EntitySyncStatus? {
        return statusString?.let { enumValueOf<EntitySyncStatus>(it) }
    }
} 