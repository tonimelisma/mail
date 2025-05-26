package net.melisma.core_db.converter

import androidx.room.TypeConverter
import net.melisma.core_data.model.WellKnownFolderType

class WellKnownFolderTypeConverter {
    @TypeConverter
    fun fromWellKnownFolderType(type: WellKnownFolderType?): String? {
        return type?.name
    }

    @TypeConverter
    fun toWellKnownFolderType(name: String?): WellKnownFolderType? {
        return name?.let {
            try {
                WellKnownFolderType.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null // Or handle unknown types, e.g., return WellKnownFolderType.OTHER
            }
        }
    }
} 