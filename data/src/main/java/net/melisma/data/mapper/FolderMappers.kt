package net.melisma.data.mapper

import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.entity.FolderEntity

// It's assumed DomainFolder will be similar to MailFolder or MailFolder will be used as DomainModel directly in VM
// For now, let's map FolderEntity to core_data.model.MailFolder for use in existing ViewModels/States

fun MailFolder.toEntity(accountId: String, localId: String): FolderEntity {
    return FolderEntity(
        id = localId, // Use a locally unique ID
        accountId = accountId,
        remoteId = this.id, // The ID from the API is the remoteId
        name = this.displayName,
        totalCount = this.totalItemCount,
        unreadCount = this.unreadItemCount,
        type = this.type.name // Convert enum to String
    )
}

fun FolderEntity.toDomainModel(): MailFolder {
    return MailFolder(
        id = this.remoteId ?: this.id, // Prefer remoteId as the canonical ID for the domain
        displayName = this.name ?: "Unnamed Folder",
        totalItemCount = this.totalCount ?: 0,
        unreadItemCount = this.unreadCount ?: 0,
        type = try {
            this.type?.let { WellKnownFolderType.valueOf(it) }
                ?: WellKnownFolderType.USER_CREATED
        } catch (e: IllegalArgumentException) {
            WellKnownFolderType.OTHER // Fallback for unknown types
        }
    )
} 