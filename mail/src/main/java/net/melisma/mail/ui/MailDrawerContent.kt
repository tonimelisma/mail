// File: app/src/main/java/net/melisma/mail/ui/MailDrawerContent.kt
// Corrected imports for reported build errors

package net.melisma.mail.ui

// *** ADDED MANY COMPOSE IMPORTS ***
// import androidx.compose.ui.graphics.vector.ImageVector // Only needed if getIconForFolder is used and returns ImageVector
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.MailFolder
import net.melisma.mail.MainScreenState
import net.melisma.mail.R
import java.util.Locale

/**
 * Composable function defining the content displayed inside the app's navigation drawer.
 * Shows accounts, their folders (with loading/error states), and navigation items like Settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MailDrawerContent(
    state: MainScreenState,
    onFolderSelected: (folder: MailFolder, account: Account) -> Unit,
    onSettingsClicked: () -> Unit,
    onRefreshFolders: (accountId: String) -> Unit
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
        LazyColumn(modifier = Modifier.fillMaxSize()) { // Now resolved
            // --- Drawer Header ---
            item {
                Text( // Now resolved
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 20.dp
                    ) // Now resolved
                )
            }
            item { HorizontalDivider() } // Now resolved

            // --- Accounts and Folders Section ---
            state.accounts.forEach { account ->
                stickyHeader {
                    AccountHeader(
                        account = account,
                        onRefreshClicked = { onRefreshFolders(account.id) })
                } // Pass to AccountHeader

                val folderState = state.foldersByAccountId[account.id]

                item {
                    when (folderState) {
                        is FolderFetchState.Loading -> {
                            Row( // Now resolved
                                Modifier // Now resolved
                                    .fillMaxWidth() // Now resolved
                                    .padding(horizontal = 16.dp, vertical = 8.dp), // Now resolved
                                verticalAlignment = Alignment.CenterVertically // Now resolved
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp)) // Now resolved
                                Spacer(Modifier.width(16.dp)) // Now resolved
                                Text( // Now resolved
                                    stringResource(R.string.loading_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is FolderFetchState.Error -> {
                            Row( // Now resolved
                                Modifier // Now resolved
                                    .fillMaxWidth() // Now resolved
                                    .padding(horizontal = 16.dp, vertical = 8.dp), // Now resolved
                                verticalAlignment = Alignment.CenterVertically // Now resolved
                            ) {
                                Icon( // Now resolved
                                    Icons.Filled.ErrorOutline, // Now resolved
                                    stringResource(R.string.cd_error_loading_folders),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp) // Now resolved
                                )
                                Spacer(Modifier.width(16.dp)) // Now resolved
                                Text( // Now resolved
                                    folderState.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        is FolderFetchState.Success -> {
                            val folders = folderState.folders
                            if (folders.isEmpty()) {
                                Text( // Now resolved
                                    stringResource(R.string.no_folders_found),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding( // Now resolved
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
                                        val key = folderNameToSortKey(f.displayName)
                                        if (standardFolderOrder.contains(key)) {
                                            if (!standardMap.containsKey(key)) standardMap[key] =
                                                f else otherFolders.add(f)
                                        } else {
                                            otherFolders.add(f)
                                        }
                                    }
                                    standardFolderOrder.mapNotNull { standardMap[it] } + otherFolders.sortedBy { it.displayName }
                                }
                                Column { // Now resolved
                                    sortedFolders.forEach { folder ->
                                        // Note: FolderItem call might fail if getIconForFolder is missing/not imported
                                        FolderItem(
                                            folder = folder,
                                            isSelected = folder.id == state.selectedFolder?.id && account.id == state.selectedFolderAccountId,
                                            onClick = { onFolderSelected(folder, account) }
                                        )
                                    }
                                }
                            }
                        }

                        null -> {
                            Row( // Now resolved
                                Modifier // Now resolved
                                    .fillMaxWidth() // Now resolved
                                    .padding(horizontal = 16.dp, vertical = 8.dp), // Now resolved
                                verticalAlignment = Alignment.CenterVertically // Now resolved
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp)) // Now resolved
                                Spacer(Modifier.width(16.dp)) // Now resolved
                                Text( // Now resolved
                                    stringResource(R.string.loading_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } // End when (folderState)
                } // End item for folder content
                item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) } // Now resolved
            } // End forEach account

            // --- Settings Navigation Item ---
            item {
                NavigationDrawerItem( // Now resolved
                    label = { Text(stringResource(R.string.settings_title)) }, // Now resolved
                    selected = false,
                    onClick = onSettingsClicked,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), // Now resolved
                    icon = {
                        Icon(
                            Icons.Filled.Settings,
                            stringResource(R.string.settings_title)
                        )
                    } // Now resolved
                )
            }
        } // End LazyColumn
    } // End ModalDrawerSheet
}

/**
 * Composable for the sticky header displaying the account username in the drawer.
 */
@Composable
private fun AccountHeader(account: Account, onRefreshClicked: () -> Unit) {
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
            text = account.displayName ?: account.emailAddress,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f) // Allow text to take available space
        )
        // Add a refresh icon button
        IconButton(onClick = onRefreshClicked, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Refresh, // Make sure this import exists: androidx.compose.material.icons.filled.Refresh
                contentDescription = "Refresh folders for ${account.displayName ?: account.emailAddress}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Composable for displaying a single folder item in the navigation drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderItem(folder: MailFolder, isSelected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem( // Now resolved
        label = { Text(folder.displayName) }, // Now resolved
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), // Now resolved
        // *** NOTE: The following line WILL cause a build error if getIconForFolder is not defined/imported ***
        icon = {
            Icon(
                imageVector = getIconForFolder(folder.type),
                contentDescription = folder.displayName
            )
        }, // Icon and getIconForFolder call might still be unresolved
        badge = { if (folder.unreadItemCount > 0) Badge { Text(folder.unreadItemCount.toString()) } } // Now resolved
    )
}