package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.repository.FolderRepository
import javax.inject.Inject

class ObserveFoldersForAccountUseCase @Inject constructor(
    private val folderRepository: FolderRepository
) {
    operator fun invoke(accountId: String): Flow<FolderFetchState?> {
        return folderRepository.observeFoldersState().map {
            it[accountId]
                ?: FolderFetchState.Error("Account ID not found in folder states") // Or handle as appropriate
        }
    }
} 