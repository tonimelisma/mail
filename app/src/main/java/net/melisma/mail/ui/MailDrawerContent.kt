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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.melisma.mail.DataState
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
    // Folder sorting logic remains the same...
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
                    println("Duplicate standard folder key found: $sortKey for ${folder.displayName}.")
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
                // --- Use state.folderDataState in the when expression ---
                when (state.folderDataState) {
                    // --- Check against DataState enum values ---
                    DataState.LOADING -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    DataState.ERROR -> {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Error: ${state.folderError ?: "Unknown error"}",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onRefreshFolders) { Text("Retry Folders") }
                        }
                    }

                    DataState.SUCCESS -> {
                        // Use the processed sortedFolders list
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
                                            if (folder.unreadItemCount > 0) {
                                                Badge { Text(folder.unreadItemCount.toString()) }
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            // Handle case where state.folders was non-null but resulted in empty sortedFolders
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No folders found.")
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = onRefreshFolders) { Text("Refresh Folders") }
                            }
                        }
                    }

                    DataState.INITIAL -> { // Handle initial state before loading starts
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Text("Loading folders...") // Or show nothing until loading state
                            // Or maybe show a placeholder/skeleton
                            Spacer(modifier = Modifier.height(24.dp)) // Add some space
                            CircularProgressIndicator() // Show loading indicator in initial state too
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

// Imports assumed to be present for other used composables/types like Column, Text, remember, IAccount, etc.
// Make sure Util.kt containing getIconForFolder is in the same package or imported.
// Import TextOverflow if needed
// import androidx.compose.ui.text.style.TextOverflow