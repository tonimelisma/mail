package net.melisma.core_data.model

/**
 * Represents local caching state of an attachment's binary content.
 * Mapping to DB column: see AttachmentEntity.downloadState (ordinal).
 */
enum class AttachmentDownloadState(val ordinalValue: Int) {
    NOT_DOWNLOADED(0),
    DOWNLOADED(1),
    FAILED(2);

    companion object {
        fun fromOrdinal(value: Int?): AttachmentDownloadState =
            when (value) {
                DOWNLOADED.ordinalValue -> DOWNLOADED
                FAILED.ordinalValue -> FAILED
                else -> NOT_DOWNLOADED
            }
    }
} 