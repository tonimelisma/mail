package net.melisma.mail.ui.settings // New subpackage for settings UI

import android.app.Activity
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.microsoft.identity.client.IAccount
import kotlinx.coroutines.launch
import net.melisma.mail.MainViewModel
import net.melisma.mail.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    activity: Activity,
    onNavigateUp: () -> Unit // Callback to navigate back
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRemoveDialog by remember { mutableStateOf<IAccount?>(null) }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
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
            Button(
                onClick = { viewModel.addAccount(activity) },
                enabled = !state.isLoadingAuthAction
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Show loading indicator inside button if auth action is happening
                    if (state.isLoadingAuthAction && state.accounts.size == state.accounts.size) { // Heuristic: assume loading is for add if no remove is pending
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), // Corrected: Import added
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(stringResource(R.string.add_account))
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.manage_accounts_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (state.accounts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_accounts_added),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(state.accounts, key = { it.id }) { account ->
                    ListItem(
                        headlineContent = {
                            Text(
                                account.username ?: stringResource(R.string.unknown_user)
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "Account"
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { showRemoveDialog = account },
                                enabled = !state.isLoadingAuthAction
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = stringResource(
                                        R.string.remove_account_cd,
                                        account.username ?: ""
                                    ),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) } // Space for FAB/Button

        } // End LazyColumn

        // Confirmation Dialog for Removing Account
        if (showRemoveDialog != null) {
            val accountToRemove = showRemoveDialog!!
            AlertDialog(
                onDismissRequest = { showRemoveDialog = null },
                title = { Text(stringResource(R.string.remove_account_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.remove_account_confirm_message,
                            accountToRemove.username ?: ""
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeAccount(activity, accountToRemove)
                            showRemoveDialog = null
                        },
                        enabled = !state.isLoadingAuthAction
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