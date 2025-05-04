// File: app/src/main/java/net/melisma/mail/MainActivity.kt
// Adds missing import for AuthState

package net.melisma.mail

// Import necessary model/UI components
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
// *** ADDED IMPORT for AuthState ***
import net.melisma.core_data.model.AuthState
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.settings.SettingsScreen
import net.melisma.mail.ui.theme.MailTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Use Hilt's delegate for ViewModel injection
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enable drawing behind system bars
        setContent {
            MailTheme { // Apply the application theme
                val context = LocalContext.current
                // Use remember to manage navigation state between main app and settings
                var showSettings by remember { mutableStateOf(false) }

                // Conditionally display SettingsScreen or MainApp
                if (showSettings) {
                    // Ensure activity context is correctly passed if needed by screen/VM actions
                    val activity = context as? Activity
                    if (activity != null) {
                        SettingsScreen(
                            viewModel = viewModel,
                            activity = activity, // Pass activity for account actions
                            onNavigateUp = {
                                showSettings = false
                            } // Callback to return to main app
                        )
                    } else {
                        // Handle error case where context is not an Activity
                        Log.e("MainActivity", "Error: Context is not an Activity in Settings path.")
                        ErrorDisplay("Critical Error: Cannot get Activity context.") // Show error UI
                    }
                } else {
                    // Pass activity context to MainApp if needed for actions like refresh triggers
                    val activity = context as? Activity
                    if (activity != null) {
                        MainApp(
                            viewModel = viewModel,
                            activity = activity, // Pass activity for potential refresh triggers
                            onNavigateToSettings = {
                                showSettings = true
                            } // Callback to navigate to settings
                        )
                    } else {
                        Log.e("MainActivity", "Error: Context is not an Activity in MainApp path.")
                        ErrorDisplay("Critical Error: Cannot get Activity context.") // Show error UI
                    }
                }
            }
        }
    }
}

/**
 * The main application composable, including navigation drawer, scaffold, and content switching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    activity: Activity, // Receive activity for potential actions
    onNavigateToSettings: () -> Unit
) {
    // Collect the latest UI state from the ViewModel lifecycle-awarely
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // State for managing the navigation drawer (open/closed)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // Coroutine scope bound to the Composable's lifecycle
    val scope = rememberCoroutineScope()
    val context = LocalContext.current // Get current context for showing toasts

    // Effect to show toast messages when the state indicates one is available
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            showToast(context, message) // Show the toast
            viewModel.toastMessageShown() // Notify ViewModel the message has been shown
        }
    }

    // Root composable for the navigation drawer pattern
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Content of the navigation drawer, defined in MailDrawerContent.kt
            MailDrawerContent(
                state = state, // Pass the full UI state
                onFolderSelected = { folder, account -> // Lambda called when a folder is clicked
                    scope.launch { drawerState.close() } // Close drawer smoothly
                    // Call ViewModel's selectFolder with the generic Account object
                    viewModel.selectFolder(folder, account)
                },
                onSettingsClicked = { // Lambda called when Settings item is clicked
                    scope.launch { drawerState.close() } // Close drawer
                    onNavigateToSettings() // Trigger navigation to SettingsScreen
                }
            )
        }
    ) {
        // Main screen structure using Scaffold
        Scaffold(
            topBar = {
                // Determine title based on selected folder or app name
                val title = state.selectedFolder?.displayName ?: stringResource(R.string.app_name)
                // Reusable top app bar component
                MailTopAppBar(
                    title = title,
                    onNavigationClick = { scope.launch { drawerState.open() } } // Open drawer on click
                )
            },
            floatingActionButton = { /* Placeholder for future FAB (e.g., Compose) */ }
        ) { innerPadding -> // Content area padding provided by Scaffold
            Box(modifier = Modifier.padding(innerPadding)) {
                // Main content switching based on the authentication state
                val currentAuthState = state.authState // Read state for clarity

                // 'when' is now exhaustive because AuthState is correctly resolved
                when (currentAuthState) {
                    is AuthState.Initializing -> {
                        // Show loading indicator during auth initialization
                        LoadingIndicator(statusText = stringResource(R.string.status_initializing_auth))
                    }

                    is AuthState.InitializationError -> {
                        // Show error message if auth initialization failed
                        AuthInitErrorContent(errorState = currentAuthState)
                    }

                    is AuthState.Initialized -> {
                        // Auth is ready, decide content based on account actions or selected folder
                        when {
                            state.isLoadingAccountAction -> {
                                // Show loading indicator during account add/remove operations
                                LoadingIndicator(statusText = stringResource(R.string.status_authenticating))
                            }

                            state.accounts.isEmpty() -> {
                                // Show prompt to add account if none exist
                                SignedOutContent(
                                    onAddAccountClick = onNavigateToSettings, // Navigate to settings to add account
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            state.selectedFolder != null -> {
                                // If a folder is selected, show the message list
                                val accountForMessages =
                                    state.accounts.find { it.id == state.selectedFolderAccountId }

                                // *** CORRECTED CALL to MessageListContent ***
                                MessageListContent(
                                    messageDataState = state.messageDataState, // Pass the state object
                                    // messages = state.messages, // REMOVED - Data is in messageDataState
                                    // messageError = state.messageError, // REMOVED - Data is in messageDataState
                                    accountContext = accountForMessages,
                                    isRefreshing = state.isMessageLoading, // ADDED - Pass refreshing status
                                    onRefresh = { viewModel.refreshMessages(activity) },
                                    onMessageClick = { messageId ->
                                        // TODO: Implement navigation to single message view
                                        showToast(
                                            context,
                                            "Clicked Message ID: $messageId (View not implemented)"
                                        )
                                    }
                                )
                            }

                            else -> {
                                // If initialized but no folder selected (e.g., after initial load)
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        stringResource(R.string.prompt_select_folder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    // No 'else' needed as AuthState is a sealed class and all cases are handled
                } // End when (currentAuthState)
            } // End Box (main content area)
        } // End Scaffold
    } // End ModalNavigationDrawer
}


// --- Helper Composables ---

/** Displays a centered CircularProgressIndicator with optional status text. */
@Composable
private fun LoadingIndicator(statusText: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (statusText != null) {
                Spacer(Modifier.height(16.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Displays a centered error message for authentication initialization failures. */
@Composable
private fun AuthInitErrorContent(errorState: AuthState.InitializationError) {
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.error_auth_init_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            // Accessing 'errorState.error' is now valid as AuthState is resolved
            val errorText =
                errorState.error?.message ?: stringResource(id = R.string.error_unknown_occurred)
            Text(
                errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Displays content shown when the user is authenticated but has no accounts added. */
@Composable
private fun SignedOutContent(onAddAccountClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                stringResource(R.string.welcome_message),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.prompt_add_account),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAddAccountClick) {
                Text(stringResource(R.string.manage_accounts_button))
            }
        }
    }
}

/** Displays a generic error message centered on the screen. */
@Composable
private fun ErrorDisplay(message: String) {
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}


/** Utility function to show an Android Toast message. */
private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}