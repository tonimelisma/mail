package net.melisma.data.mapper

import net.melisma.core_data.model.MailFolder // This is the API model
import net.melisma.core_db.entity.FolderEntity

// It's assumed DomainFolder will be similar to MailFolder or MailFolder will be used as DomainModel directly in VM
// For now, let's map FolderEntity to core_data.model.MailFolder for use in existing ViewModels/States

fun MailFolder.toEntity(accountId: String): FolderEntity {
    return FolderEntity(
        id = this.id,
        accountId = accountId,
        displayName = this.displayName,
        totalItemCount = this.totalItemCount,
        unreadItemCount = this.unreadItemCount,
        type = this.type
    )
}

fun FolderEntity.toDomainModel(): MailFolder { // Mapping to MailFolder as the domain/UI model for now
    return MailFolder(
        id = this.id,
        displayName = this.displayName,
        totalItemCount = this.totalItemCount,
        unreadItemCount = this.unreadItemCount,
        type = this.type
        // accountId is part of FolderEntity but not MailFolder; it's used for grouping/fetching
    )
} 