package net.melisma.data.mapper

import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.entity.FolderEntity

// It's assumed DomainFolder will be similar to MailFolder or MailFolder will be used as DomainModel directly in VM
// For now, let's map FolderEntity to core_data.model.MailFolder for use in existing ViewModels/States

fun MailFolder.toEntity(accountId: String, localId: String): FolderEntity {
    return FolderEntity(
        id = localId, // This will now always be a UUID provided by the caller
        accountId = accountId,
        remoteId = this.id, // The ID from the API is the remoteId
        name = this.displayName,
        wellKnownType = this.type, // ADDED: MailFolder.type is WellKnownFolderType enum
        totalCount = this.totalItemCount,
        unreadCount = this.unreadItemCount
        // Other FolderEntity fields like parentFolderId, syncStatus etc., are not set here.
        // They would be set either by default in FolderEntity or updated later by sync workers if needed.
    )
}

fun FolderEntity.toDomainModel(): MailFolder {
    return MailFolder(
        id = this.id, // ALWAYS use the local UUID as the ID for the domain model
        displayName = this.name ?: "Unnamed Folder",
        totalItemCount = this.totalCount ?: 0,
        unreadItemCount = this.unreadCount ?: 0,
        type = this.wellKnownType
            ?: WellKnownFolderType.USER_CREATED, // Use new wellKnownType field
        // Add remoteId to MailFolder if it's needed by the UI for other purposes,
        // or if parts of the ViewModel layer still need to reference folders by their remoteId transiently.
        // For example: remoteId = this.remoteId 
    )
} 