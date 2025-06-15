package net.melisma.core_db.converter

import androidx.room.TypeConverter
import net.melisma.core_db.model.PendingActionStatus

class PendingActionStatusConverter {
    @TypeConverter
    fun fromStatus(status: PendingActionStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(status: String): PendingActionStatus {
        return PendingActionStatus.valueOf(status)
    }
} 