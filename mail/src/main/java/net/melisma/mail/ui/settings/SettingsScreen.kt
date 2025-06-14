// File: app/src/main/java/net/melisma/mail/ui/settings/SettingsScreen.kt
// Updated to use generic Account type and add missing Column import

package net.melisma.mail.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Account
import net.melisma.core_data.preferences.CacheSizePreference
import net.melisma.core_data.preferences.DownloadPreference
import net.melisma.core_data.preferences.InitialSyncDurationPreference
import net.melisma.core_data.preferences.MailViewModePreference
import net.melisma.mail.MainScreenState
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
    var showCacheSizeDialog by remember { mutableStateOf(false) } // New state for cache size dialog
    var showInitialSyncDurationDialog by remember { mutableStateOf(false) } // New state
    var showBodyDownloadPreferenceDialog by remember { mutableStateOf(false) }
    var showAttachmentDownloadPreferenceDialog by remember { mutableStateOf(false) }

    // Observe toast message changes (remains the same)
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
            viewModel.toastMessageShown()
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
                    onClick = { viewModel.initiateSignIn(Account.PROVIDER_TYPE_MS, activity) },
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
                        Text(stringResource(R.string.settings_add_microsoft_account_button))
                    }
                }

                // Google account button
                Button(
                    // Trigger add Google account action in ViewModel
                    onClick = {
                        viewModel.initiateSignIn(
                            Account.PROVIDER_TYPE_GOOGLE,
                            activity
                        )
                    },
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
                        Text(stringResource(R.string.settings_add_google_account_button))
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
                        headlineContent = { Text(account.displayName ?: account.emailAddress) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.account_provider_type_label,
                                    account.providerType
                                )
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = stringResource(R.string.cd_account_icon)
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { showRemoveDialog = account }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = stringResource(
                                        R.string.remove_account_cd,
                                        account.displayName ?: account.emailAddress
                                    ), // Using existing formatted string
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

            // NEW CACHE SETTINGS SECTION
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.settings_cache_preferences_header), // Placeholder: "Cache Settings"
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                val currentCacheSizePref = state.currentCacheSizePreference
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_cache_size_title)) }, // Placeholder: "Storage Cache Size"
                    supportingContent = {
                        Text(
                            // Format the byte value into a readable string (e.g., "500 MB", "1 GB")
                            text = formatCacheSize(currentCacheSizePref.bytes) // Placeholder: will create formatCacheSize helper
                        )
                    },
                    modifier = Modifier.clickable { showCacheSizeDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }

            // Initial Sync Duration Preference Item
            item {
                InitialSyncDurationPreferenceItem(state = state, onClick = { showInitialSyncDurationDialog = true })
            }

            // DOWNLOAD PREFERENCES SECTION
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.settings_download_preferences_header), // New String Resource
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_body_download_title)) }, // New String
                    supportingContent = { Text(state.currentBodyDownloadPreference.displayName) },
                    modifier = Modifier.clickable { showBodyDownloadPreferenceDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_attachment_download_title)) }, // New String
                    supportingContent = { Text(state.currentAttachmentDownloadPreference.displayName) },
                    modifier = Modifier.clickable { showAttachmentDownloadPreferenceDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
            // END DOWNLOAD PREFERENCES SECTION

            // ABOUT SECTION (Example - could be more elaborate)
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.settings_about_header), // New String Resource
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Spacer for FAB
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

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
                            accountToRemove.displayName ?: accountToRemove.emailAddress
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.signOut(accountToRemove) // Changed to signOut
                        showRemoveDialog = null
                    }) {
                        Text(stringResource(R.string.remove_action)) 
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoveDialog = null }) {
                        Text(stringResource(R.string.cancel_action)) 
                    }
                }
            )
        }
    }

    if (showCacheSizeDialog) {
        CacheSizeSelectionDialog(
            availableSizes = state.availableCacheSizes,
            currentSize = state.currentCacheSizePreference,
            onDismiss = { showCacheSizeDialog = false },
            onSizeSelected = {
                viewModel.setCacheSizePreference(it)
                showCacheSizeDialog = false
            }
        )
    }

    if (showInitialSyncDurationDialog) { // New Dialog instance
        InitialSyncDurationSelectionDialog(
            currentPreference = state.currentInitialSyncDurationPreference,
            availableDurations = state.availableInitialSyncDurations,
            onDismiss = { showInitialSyncDurationDialog = false },
            onSelected = {
                viewModel.setInitialSyncDurationPreference(it)
                showInitialSyncDurationDialog = false
            }
        )
    }

    if (showBodyDownloadPreferenceDialog) {
        DownloadPreferenceSelectionDialog(
            title = stringResource(R.string.settings_body_download_dialog_title),
            availablePreferences = state.availableDownloadPreferences,
            currentPreference = state.currentBodyDownloadPreference,
            onDismiss = { showBodyDownloadPreferenceDialog = false },
            onSelected = {
                viewModel.setBodyDownloadPreference(it)
                showBodyDownloadPreferenceDialog = false
            }
        )
    }

    if (showAttachmentDownloadPreferenceDialog) {
        DownloadPreferenceSelectionDialog(
            title = stringResource(R.string.settings_attachment_download_dialog_title),
            availablePreferences = state.availableDownloadPreferences,
            currentPreference = state.currentAttachmentDownloadPreference,
            onDismiss = { showAttachmentDownloadPreferenceDialog = false },
            onSelected = {
                viewModel.setAttachmentDownloadPreference(it)
                showAttachmentDownloadPreferenceDialog = false
            }
        )
    }
}

@Composable
fun CacheSizeSelectionDialog(
    availableSizes: List<CacheSizePreference>,
    currentSize: CacheSizePreference,
    onDismiss: () -> Unit,
    onSizeSelected: (CacheSizePreference) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cache_size_dialog_title)) }, // Placeholder: "Select Cache Size"
        text = {
            Column {
                availableSizes.forEach { sizeOption ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSizeSelected(sizeOption) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatCacheSize(sizeOption.bytes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (sizeOption == currentSize) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

// Helper function to format cache size in bytes to a readable string
@Composable
fun formatCacheSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> {
            val gb = bytes / (1024 * 1024 * 1024)
            stringResource(R.string.cache_size_gb, gb) // Placeholder: "%1$d GB"
        }
        bytes >= 1024 * 1024 -> {
            val mb = bytes / (1024 * 1024)
            stringResource(R.string.cache_size_mb, mb) // Placeholder: "%1$d MB"
        }
        else -> {
            pluralStringResource(R.plurals.cache_size_bytes, bytes.toInt(), bytes)
        }
    }
}

@Composable
fun InitialSyncDurationPreferenceItem(state: MainScreenState, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_initial_sync_duration_title)) },
        supportingContent = { Text(state.currentInitialSyncDurationPreference.displayName) },
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun InitialSyncDurationSelectionDialog(
    currentPreference: InitialSyncDurationPreference,
    availableDurations: List<InitialSyncDurationPreference>,
    onDismiss: () -> Unit,
    onSelected: (InitialSyncDurationPreference) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_initial_sync_duration_dialog_title)) }, // Needs new string
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        dismissButton = null, // Options are presented in the item list
        text = { // Moved LazyColumn to the text parameter
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                items(availableDurations) { duration ->
                    TextButton(
                        onClick = { onSelected(duration) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                duration.displayName,
                                style = if (duration == currentPreference) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun DownloadPreferenceSelectionDialog(
    title: String,
    availablePreferences: List<DownloadPreference>,
    currentPreference: DownloadPreference,
    onDismiss: () -> Unit,
    onSelected: (DownloadPreference) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        dismissButton = null, 
        text = {
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                items(availablePreferences) { preference ->
                    TextButton(
                        onClick = { onSelected(preference) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                preference.displayName,
                                style = if (preference == currentPreference) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    )
}
