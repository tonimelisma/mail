package net.melisma.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.melisma.mail.DataState
import net.melisma.mail.MailFolder
import net.melisma.mail.MainScreenState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDrawerContent(
    state: MainScreenState,
    onFolderSelected: (MailFolder) -> Unit,
    onSignOutClick: () -> Unit,
    onRefreshFolders: () -> Unit // Kept for potential future use
) {
    // Folder sorting logic
    val standardFolderOrder = listOf(
        "inbox", "drafts", "sent items", "spam", "trash", "archive", "all mail"
    )
    val folderNameToSortKey: (String) -> String = { name ->
        val lowerName = name.lowercase(Locale.ROOT)
        when (lowerName) {
            "junk email" -> "spam"
            "deleted items" -> "trash"
            "all mail" -> "archive"
            else -> lowerName
        }
    }
    val sortedFolders = remember(state.folders) {
        val folders = state.folders ?: return@remember emptyList()
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
        val sortedStandard = standardFolderOrder.mapNotNull { standardFoldersMap[it] }
        val sortedOther = otherFolders.sortedBy { it.displayName }
        sortedStandard + sortedOther
    }

    // Drawer UI
    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxSize()) {
            // Account Header
            if (state.currentAccount != null) {
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 8.dp
                    )
                ) {
                    Text(
                        text = state.currentAccount.username ?: "Unknown User",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
            }

            // Folders Header
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)) {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Folders List Area
            Box(modifier = Modifier.weight(1f)) {
                when (state.folderDataState) {
                    DataState.LOADING, DataState.INITIAL -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    DataState.ERROR -> {
                        // Display user-friendly error message without explicit button
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp), // Fill space
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudOff,
                                contentDescription = "Error",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant // Less alarming color
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.folderError
                                    ?: "Couldn't load folders", // Use mapped error
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Check connection and pull down message list to refresh.", // Inform user
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DataState.SUCCESS -> {
                        if (sortedFolders.isNotEmpty()) {
                            LazyColumn {
                                items(sortedFolders, key = { it.id }) { folder ->
                                    NavigationDrawerItem(
                                        label = { Text(folder.displayName) },
                                        selected = folder.id == state.selectedFolder?.id,
                                        onClick = { onFolderSelected(folder) },
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                        icon = {
                                            Icon(
                                                getIconForFolder(folder.displayName),
                                                contentDescription = folder.displayName
                                            )
                                        },
                                        badge = {
                                            if (folder.unreadItemCount > 0) Badge {
                                                Text(
                                                    folder.unreadItemCount.toString()
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            // Empty state
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = "Empty",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No folders found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } // End Folders List Area

            // Sign Out
            if (state.currentAccount != null) {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Sign Out") },
                    selected = false,
                    onClick = onSignOutClick,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    }
}

// Need Util.kt containing getIconForFolder in the same project.
// Imports assumed present: androidx.compose.ui.text.style.TextOverflow, etc.