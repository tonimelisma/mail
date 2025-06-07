package net.melisma.core_db.converter

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class PayloadConverter {
    @TypeConverter
    fun fromPayloadMap(payload: Map<String, String?>?): String? {
        return payload?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toPayloadMap(jsonString: String?): Map<String, String?>? {
        return jsonString?.let { Json.decodeFromString<Map<String, String?>>(it) }
    }
} 