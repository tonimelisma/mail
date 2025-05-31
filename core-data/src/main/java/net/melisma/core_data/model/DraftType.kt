package net.melisma.core_data.model

import kotlinx.serialization.Serializable

@Serializable
enum class DraftType {
    NEW,
    REPLY,
    FORWARD
} 