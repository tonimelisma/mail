// File: app/src/main/java/net/melisma/mail/ui/settings/SettingsScreen.kt
// Updated to use generic Account type and add missing Column import

package net.melisma.mail.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Account
import net.melisma.core_data.preferences.MailViewModePreference
import net.melisma.mail.MainViewModel
import net.melisma.mail.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    activity: Activity,
    onNavigateUp: () -> Unit // Callback to navigate back
) {
    // Collect the state which now contains List<Account>
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // State for the confirmation dialog, holding the generic Account to remove
    var showRemoveDialog by remember { mutableStateOf<Account?>(null) }

    // Observe toast message changes (remains the same)
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            // Added more specific check based on repository messages if needed,
            // but checking for keywords is okay for now.
            if (message.contains("Account added") || message.contains("Account removed") || message.contains(
                    "Error"
                )
            ) {
                scope.launch { snackbarHostState.showSnackbar(message) }
                viewModel.toastMessageShown()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_up)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column { // Using Column for multiple FABs or grouped buttons
                // Microsoft account button
                Button(
                    // Trigger add Microsoft account action in ViewModel
                    onClick = { viewModel.addAccount(activity) },
                    // Use isLoadingAccountAction from the updated state
                    enabled = !state.isLoadingAccountAction,
                    modifier = Modifier.padding(bottom = 8.dp) // Add some spacing between buttons
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Show loading indicator based on isLoadingAccountAction
                        if (state.isLoadingAccountAction) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("Add Microsoft Account")
                    }
                }

                // Google account button
                Button(
                    // Trigger add Google account action in ViewModel
                    onClick = { viewModel.addGoogleAccount(activity) },
                    // Use isLoadingAccountAction from the updated state
                    enabled = !state.isLoadingAccountAction
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Show loading indicator based on isLoadingAccountAction
                        if (state.isLoadingAccountAction) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("Add Google Account")
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 16.dp) // Add some padding at the top of the list
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_manage_accounts_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Check the generic accounts list from state
            if (state.accounts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_accounts_added),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // Iterate through the generic Account list
                items(state.accounts, key = { it.id }) { account ->
                    // Replacing AccountItem with a basic ListItem to resolve build error
                    ListItem(
                        headlineContent = { Text(account.username) },
                        supportingContent = { Text("Provider: ${account.providerType}") }, // Example detail
                        leadingContent = {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "Account"
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { showRemoveDialog = account }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Remove account",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }

            // NEW VIEW PREFERENCES SECTION
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.settings_view_preferences_header), // New String Resource
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                val isThreadMode = state.currentViewMode == MailViewModePreference.THREADS
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_view_mode_title)) }, // New String
                    supportingContent = {
                        Text(
                            if (isThreadMode) stringResource(R.string.settings_view_mode_threads_desc) // New String
                            else stringResource(R.string.settings_view_mode_messages_desc) // New String
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = isThreadMode,
                            onCheckedChange = { wantsThreadMode ->
                                val newMode =
                                    if (wantsThreadMode) MailViewModePreference.THREADS else MailViewModePreference.MESSAGES
                                viewModel.setViewModePreference(newMode)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface), // Ensure background contrast
                    modifier = Modifier.clickable {
                        val newMode =
                            if (isThreadMode) MailViewModePreference.MESSAGES else MailViewModePreference.THREADS
                        viewModel.setViewModePreference(newMode)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
            // END NEW VIEW PREFERENCES SECTION

            item { Spacer(modifier = Modifier.height(120.dp)) } // For padding at the bottom

        } // End LazyColumn

        // Confirmation Dialog uses the generic Account stored in showRemoveDialog state
        if (showRemoveDialog != null) {
            val accountToRemove = showRemoveDialog!! // Now holds a generic Account
            AlertDialog(
                onDismissRequest = { showRemoveDialog = null },
                title = { Text(stringResource(R.string.remove_account_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.remove_account_confirm_message,
                            accountToRemove.username // Use username from generic Account
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // Call the updated removeAccount in ViewModel with generic Account
                            viewModel.removeAccount(activity, accountToRemove)
                            showRemoveDialog = null
                        },
                        // Use isLoadingAccountAction from state
                        enabled = !state.isLoadingAccountAction
                    ) {
                        Text(stringResource(R.string.remove_action).uppercase())
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoveDialog = null }) {
                        Text(stringResource(R.string.cancel_action).uppercase())
                    }
                }
            )
        }
    } // End Scaffold
}
