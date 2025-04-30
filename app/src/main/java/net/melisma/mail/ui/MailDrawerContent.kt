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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.melisma.mail.MailFolder
import net.melisma.mail.MainScreenState
import java.util.Locale

// Navigation Drawer Content
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDrawerContent(
    state: MainScreenState,
    onFolderSelected: (MailFolder) -> Unit,
    onSignOutClick: () -> Unit,
    onRefreshFolders: () -> Unit
) {
    // Define the desired order using preferred display names (lowercase for matching)
    val standardFolderOrder = listOf(
        "inbox",
        "drafts",
        "sent items",
        "spam", // User preference
        "trash", // User preference
        "archive", // User preference (matching Archive or All Mail)
        "all mail" // Explicitly include if it can appear separately
    )

    // Define mapping for matching API names to preferred sort order keys
    val folderNameToSortKey: (String) -> String = { name ->
        val lowerName = name.lowercase(Locale.ROOT)
        when (lowerName) {
            "junk email" -> "spam"
            "deleted items" -> "trash"
            "all mail" -> "archive" // Map "All Mail" to "archive" for sorting/icon if needed
            else -> lowerName
        }
    }

    val sortedFolders = remember(state.folders) {
        val folders = state.folders ?: return@remember emptyList()

        val standardFoldersMap = mutableMapOf<String, MailFolder>()
        val otherFolders = mutableListOf<MailFolder>()

        // Partition folders
        folders.forEach { folder ->
            val sortKey = folderNameToSortKey(folder.displayName)
            if (standardFolderOrder.contains(sortKey)) {
                // Handle potential duplicates (e.g., both Archive and All Mail exist mapping to 'archive')
                if (!standardFoldersMap.containsKey(sortKey)) {
                    standardFoldersMap[sortKey] = folder
                } else {
                    // Log and add to other if a key collision happens (e.g., Archive and All Mail)
                    println("Duplicate standard folder key found: $sortKey for ${folder.displayName}. Keeping existing: ${standardFoldersMap[sortKey]?.displayName}")
                    otherFolders.add(folder)
                }
            } else {
                otherFolders.add(folder)
            }
        }

        // Get standard folders in the desired order
        val sortedStandard = standardFolderOrder.mapNotNull { standardFoldersMap[it] }

        // Sort other folders alphabetically
        val sortedOther = otherFolders.sortedBy { it.displayName }

        sortedStandard + sortedOther
    }


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
                        overflow = TextOverflow.Ellipsis // Requires the import
                    )
                }
                Divider(modifier = Modifier.padding(bottom = 8.dp))
            }

            // Folders Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Folders List Area
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoadingFolders -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    state.folderError != null -> {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Error: ${state.folderError}",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onRefreshFolders) { Text("Retry Folders") }
                        }
                    }

                    sortedFolders.isNotEmpty() -> {
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
                                    }, // Uses helper from Util.kt
                                    badge = {
                                        if (folder.unreadItemCount > 0) {
                                            Badge { Text(folder.unreadItemCount.toString()) }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    state.folders != null && sortedFolders.isEmpty() -> {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No folders found.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onRefreshFolders) { Text("Refresh Folders") }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Loading folders...")
                        }
                    }
                }
            } // End Folders List Area

            // Sign Out
            if (state.currentAccount != null) {
                Divider(modifier = Modifier.padding(top = 8.dp))
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

// Imports assumed to be present for other used composables/types like Column, Text, remember, IAccount, etc.
// Make sure Util.kt containing getIconForFolder is in the same package or imported.