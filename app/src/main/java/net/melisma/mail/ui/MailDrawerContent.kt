// File: app/src/main/java/net/melisma/mail/ui/MailDrawerContent.kt
// Corrected imports and when statement for FolderFetchState

package net.melisma.mail.ui

// Import generic Account
// Import FolderFetchState from its model package
// Import Util functions if not in the same package
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
import net.melisma.mail.Account
import net.melisma.mail.MailFolder
import net.melisma.mail.MainScreenState
import net.melisma.mail.R
import net.melisma.mail.model.FolderFetchState
import java.util.Locale

/**
 * Composable function defining the content displayed inside the app's navigation drawer.
 * Shows accounts, their folders (with loading/error states), and navigation items like Settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MailDrawerContent(
    state: MainScreenState, // Takes the updated state object
    onFolderSelected: (folder: MailFolder, account: Account) -> Unit, // Callback takes generic Account
    onSettingsClicked: () -> Unit,
) {
    // --- Define folder sorting logic ONCE using remember ---
    val standardFolderOrder = remember {
        listOf("inbox", "drafts", "sent items", "spam", "trash", "archive", "all mail")
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
    // --- End folder sorting logic ---

    ModalDrawerSheet {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // --- Drawer Header ---
            item {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }
            item { HorizontalDivider() }

            // --- Accounts and Folders Section ---
            state.accounts.forEach { account ->
                stickyHeader { AccountHeader(account = account) }

                val folderState = state.foldersByAccountId[account.id]

                item {
                    // Use a 'when' expression to handle all possible states of FolderFetchState
                    // Ensure all branches (Loading, Success, Error, null) are handled.
                    when (folderState) {
                        is FolderFetchState.Loading -> { // Explicitly handle Loading
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    stringResource(R.string.loading_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is FolderFetchState.Error -> {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    stringResource(R.string.cd_error_loading_folders),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                // Access the 'error' property from the Error state
                                Text(
                                    folderState.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        is FolderFetchState.Success -> {
                            // Access the 'folders' property from the Success state
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
                                    )
                                )
                            } else {
                                val sortedFolders = remember(folders) {
                                    val standardMap = mutableMapOf<String, MailFolder>()
                                    val otherFolders = mutableListOf<MailFolder>()
                                    folders.forEach { f ->
                                        // Access 'displayName' property of MailFolder
                                        val key = folderNameToSortKey(f.displayName)
                                        if (standardFolderOrder.contains(key)) {
                                            if (!standardMap.containsKey(key)) standardMap[key] =
                                                f else otherFolders.add(f)
                                        } else {
                                            otherFolders.add(f)
                                        }
                                    }
                                    // Access 'displayName' property for sorting
                                    standardFolderOrder.mapNotNull { standardMap[it] } + otherFolders.sortedBy { it.displayName }
                                }
                                Column {
                                    sortedFolders.forEach { folder ->
                                        FolderItem(
                                            folder = folder,
                                            isSelected = folder.id == state.selectedFolder?.id && account.id == state.selectedFolderAccountId,
                                            onClick = { onFolderSelected(folder, account) }
                                        )
                                    }
                                }
                            }
                        }

                        null -> { // Explicitly handle the null case (before loading starts)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Show loading indicator or placeholder text for null state
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    stringResource(R.string.loading_folders), // Or "Initializing..."
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } // End when (folderState)
                } // End item for folder content
                item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
            } // End forEach account

            // --- Settings Navigation Item ---
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings_title)) },
                    selected = false,
                    onClick = onSettingsClicked,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    icon = { Icon(Icons.Filled.Settings, stringResource(R.string.settings_title)) }
                )
            }
        } // End LazyColumn
    } // End ModalDrawerSheet
}

/**
 * Composable for the sticky header displaying the account username in the drawer.
 */
@Composable
private fun AccountHeader(account: Account) {
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
            text = account.username,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Composable for displaying a single folder item in the navigation drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderItem(folder: MailFolder, isSelected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(folder.displayName) },
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        icon = { Icon(getIconForFolder(folder.displayName), folder.displayName) },
        badge = { if (folder.unreadItemCount > 0) Badge { Text(folder.unreadItemCount.toString()) } }
    )
}
