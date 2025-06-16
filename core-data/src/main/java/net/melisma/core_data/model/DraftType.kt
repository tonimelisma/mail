package net.melisma.core_data.model

import kotlinx.serialization.Serializable

@Serializable
enum class DraftType {
    NEW,
    REPLY,
    REPLY_ALL,
    FORWARD
} 