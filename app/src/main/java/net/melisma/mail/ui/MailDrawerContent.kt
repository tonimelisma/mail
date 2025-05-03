package net.melisma.mail.ui

// Removed unused items import: import androidx.compose.foundation.lazy.items
// --- Ensure necessary icons are imported (used by getIconForFolder implicitly via Util.kt or directly) ---
// Icons needed by getIconForFolder from Util.kt will be resolved via that file's imports
// --- End icon imports ---
// Removed unused ImageVector import: import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.microsoft.identity.client.IAccount
import net.melisma.mail.FolderFetchState
import net.melisma.mail.MailFolder
import net.melisma.mail.MainScreenState
import net.melisma.mail.R
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MailDrawerContent(
    state: MainScreenState,
    onFolderSelected: (folder: MailFolder, account: IAccount) -> Unit,
    onSettingsClicked: () -> Unit,
) {
    // Folder sorting logic
    val standardFolderOrder = remember {
        listOf(
        "inbox", "drafts", "sent items", "spam", "trash", "archive", "all mail"
        )
    }
    val folderNameToSortKey: (String) -> String = remember {
        { name ->
        val lowerName = name.lowercase(Locale.ROOT)
        when (lowerName) {
            "junk email" -> "spam"
            "deleted items" -> "trash"
            "all mail" -> "archive"
            else -> lowerName
        }
        }
    }

    ModalDrawerSheet {
        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // --- Header ---
            item {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }
            item { HorizontalDivider() }

            // --- Accounts and their Folders ---
            state.accounts.forEach { account ->
                stickyHeader {
                    AccountHeader(account = account)
                }

                val folderState = state.foldersByAccountId[account.id]

                item { // Wrap content in items
                    when (folderState) {
                        is FolderFetchState.Loading, null -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    stringResource(R.string.loading_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is FolderFetchState.Error -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = stringResource(R.string.cd_error_loading_folders),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    folderState.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        is FolderFetchState.Success -> {
                            val folders = folderState.folders
                            if (folders.isEmpty()) {
                                Text(
                                    stringResource(R.string.no_folders_found),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = 68.dp,
                                        top = 8.dp,
                                        bottom = 8.dp
                                    ) // Indent like items
                                )
                            } else {
                                val sortedFolders = remember(folders) { // Remember sorted list
                                    val standardFoldersMap = mutableMapOf<String, MailFolder>()
                                    val otherFolders = mutableListOf<MailFolder>()
                                    folders.forEach { folder ->
                                        val sortKey = folderNameToSortKey(folder.displayName)
                                        if (standardFolderOrder.contains(sortKey)) {
                                            if (!standardFoldersMap.containsKey(sortKey)) {
                                                standardFoldersMap[sortKey] = folder
                                            } else {
                                                otherFolders.add(folder)
                                            }
                                        } else {
                                            otherFolders.add(folder)
                                        }
                                    }
                                    val sortedStandard =
                                        standardFolderOrder.mapNotNull { standardFoldersMap[it] }
                                    val sortedOther = otherFolders.sortedBy { it.displayName }
                                    sortedStandard + sortedOther
                                }

                                Column { // Wrap folders in a Column within the item
                                    sortedFolders.forEach { folder ->
                                        FolderItem(
                                            folder = folder,
                                            isSelected = folder.id == state.selectedFolder?.id && account.id == state.selectedFolderAccountId,
                                            // Use the getIconForFolder from Util.kt (implicitly, as it's in the same package)
                                            onClick = { onFolderSelected(folder, account) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } // End item wrapper
                item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
            } // End forEach account

            // --- Settings Item ---
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings_title)) },
                    selected = false,
                    onClick = onSettingsClicked,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    icon = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                )
            }
        } // End LazyColumn
    } // End ModalDrawerSheet
}

@Composable
private fun AccountHeader(account: IAccount) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = "Account",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = account.username ?: stringResource(R.string.unknown_user),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderItem(
    folder: MailFolder,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(folder.displayName) },
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        icon = {
            Icon(
                // Use the getIconForFolder from Util.kt (implicitly, as it's in the same package)
                imageVector = getIconForFolder(folder.displayName),
                contentDescription = folder.displayName
            )
        },
        badge = {
            if (folder.unreadItemCount > 0) Badge {
                Text(folder.unreadItemCount.toString())
            }
        }
    )
}