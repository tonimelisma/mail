package net.melisma.core_db.converter

import androidx.room.TypeConverter
import net.melisma.core_db.model.PendingActionStatus

class PendingActionStatusListConverter {
    @TypeConverter
    fun fromStatusList(statuses: List<PendingActionStatus>?): String? {
        return statuses?.joinToString(",") { it.name }
    }

    @TypeConverter
    fun toStatusList(statusString: String?): List<PendingActionStatus>? {
        return statusString?.split(",")?.map { PendingActionStatus.valueOf(it) }
    }
} 