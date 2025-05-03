package net.melisma.mail

// Assuming SettingsScreen is in this package based on previous step
import android.app.Activity
import android.content.Context
import android.os.Bundle
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
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.launch
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.settings.SettingsScreen
import net.melisma.mail.ui.theme.MailTheme

class MainActivity : ComponentActivity() {

    // Lazily initialize MicrosoftAuthManager, providing context and config resource ID.
    private val microsoftAuthManager: MicrosoftAuthManager by lazy {
        MicrosoftAuthManager(context = applicationContext, configResId = R.raw.auth_config)
    }

    // Obtain the MainViewModel using the activity-ktx delegate.
    // The ViewModel is scoped to this Activity's lifecycle.
    // We provide a custom factory (MainViewModelFactory) because the ViewModel
    // requires dependencies (context, authManager) in its constructor.
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(applicationContext, microsoftAuthManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enable drawing behind system bars.
        setContent {
            // Apply the app's theme.
            MailTheme {
                val activity = LocalContext.current as Activity
                // State to control whether the Settings screen is shown.
                var showSettings by remember { mutableStateOf(false) }

                // Display either the Settings screen or the main app content.
                if (showSettings) {
                    SettingsScreen(
                        viewModel = viewModel,
                        activity = activity,
                        onNavigateUp = { showSettings = false } // Callback to close settings.
                    )
                } else {
                    MainApp(
                        viewModel = viewModel,
                        activity = activity,
                        onNavigateToSettings = {
                            showSettings = true // Callback to open settings.
                        }
                    )
                }
            }
        }
    }
}

/**
 * The main application Composable, including the navigation drawer and scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    activity: Activity,
    onNavigateToSettings: () -> Unit
) {
    // Collect the UI state from the ViewModel in a lifecycle-aware manner.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Remember the state for the navigation drawer (open/closed).
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // Remember a CoroutineScope bound to this Composable's lifecycle.
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Effect to show toast messages when they appear in the state.
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            showToast(context, message)
            // Notify the ViewModel that the message has been shown.
            viewModel.toastMessageShown()
        }
    }

    // Root Composable for the navigation drawer pattern.
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Content of the drawer (account list, folders, settings).
            MailDrawerContent(
                state = state,
                onFolderSelected = { folder, account ->
                    scope.launch { drawerState.close() } // Close drawer on selection.
                    viewModel.selectFolder(folder, account) // Notify ViewModel.
                },
                onSettingsClicked = {
                    scope.launch { drawerState.close() } // Close drawer.
                    onNavigateToSettings() // Navigate to Settings screen.
                }
            )
        }
    ) {
        // Provides standard app layout structure (app bar, content area, FAB).
        Scaffold(
            topBar = {
                // Determine the title for the app bar.
                val title = state.selectedFolder?.displayName ?: stringResource(R.string.app_name)
                MailTopAppBar(
                    title = title,
                    // Action to open the navigation drawer.
                    onNavigationClick = { scope.launch { drawerState.open() } }
                )
            },
            floatingActionButton = { /* Placeholder for potential FAB */ }
        ) { innerPadding ->
            // Content area of the Scaffold.
            Box(modifier = Modifier.padding(innerPadding)) {
                // Determine which main content UI to show based on the current state.
                when {
                    // Show loading indicator during auth initialization or actions.
                    state.isLoadingAuthAction || (!state.isAuthInitialized && state.authInitializationError == null) -> {
                        LoadingIndicator(
                            isInitializing = !state.isAuthInitialized && state.authInitializationError == null,
                            isAuthenticating = state.isLoadingAuthAction
                        )
                    }
                    // Show error if authentication initialization failed.
                    !state.isAuthInitialized && state.authInitializationError != null -> {
                        AuthInitErrorContent(state = state)
                    }
                    // Show prompt to add account if auth is ready but no accounts exist.
                    state.accounts.isEmpty() && state.isAuthInitialized -> {
                        SignedOutContent(
                            isAuthInitialized = true,
                            authInitializationError = null,
                            authErrorUserMessage = null,
                            onAddAccountClick = onNavigateToSettings, // Navigate to Settings to add account.
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Show message list if a folder is selected.
                    state.selectedFolder != null -> {
                        // Find the account associated with the selected folder.
                        val accountForMessages =
                            state.accounts.find { it.id == state.selectedFolderAccountId }
                        MessageListContent(
                            messageDataState = state.messageDataState,
                            messages = state.messages,
                            messageError = state.messageError,
                            accountContext = accountForMessages, // Pass the account for context header.
                            onRefresh = { viewModel.refreshMessages(activity) }, // Trigger refresh in ViewModel.
                            onMessageClick = { messageId ->
                                // Placeholder action for clicking a message.
                                showToast(context, "Clicked: $messageId")
                            }
                        )
                    }
                    // Default case: Auth ready, accounts exist, but no folder selected yet.
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.prompt_select_folder),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } // End when
            } // End Box
        } // End Scaffold
    } // End ModalNavigationDrawer
}


// --- Helper Composables ---

/** Displays a centered loading indicator and status text. */
@Composable
private fun LoadingIndicator(isInitializing: Boolean, isAuthenticating: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            if (isInitializing) Text(stringResource(R.string.status_initializing_auth))
            else if (isAuthenticating) Text(stringResource(R.string.status_authenticating))
        }
    }
}

/** Displays a centered error message for authentication initialization failures. */
@Composable
private fun AuthInitErrorContent(state: MainScreenState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.error_auth_init_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            // Display specific error details if available.
            val errorText = state.authErrorUserMessage ?: state.authInitializationError?.message
            ?: stringResource(id = R.string.error_unknown_occurred)
            Text(
                errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Displays content shown when authentication is initialized but no accounts are added. */
@Composable
fun SignedOutContent( // Now means "No Accounts" state
    isAuthInitialized: Boolean, authInitializationError: MsalException?,
    authErrorUserMessage: String?, onAddAccountClick: () -> Unit, modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.welcome_message))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.prompt_add_account), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            // Button to navigate to where accounts can be managed/added.
            Button(onClick = onAddAccountClick, enabled = isAuthInitialized) {
                Text(stringResource(R.string.manage_accounts_button))
            }
        }
    }
}

// --- Utility Function ---

/** Shows a short toast message. */
private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}