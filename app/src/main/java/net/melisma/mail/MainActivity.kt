// File: app/src/main/java/net/melisma/mail/MainActivity.kt
// Cleaned: Removed duplicate composables, fixed imports and state usage

package net.melisma.mail

// Import necessary Compose components that were missing
// Keep MsalException if used in AuthInitErrorContent display logic
// Import generic Account (no longer need IAccount here)
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
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.settings.SettingsScreen
import net.melisma.mail.ui.theme.MailTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Use Hilt's delegate - MainViewModelFactory MUST be deleted
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MailTheme {
                val context = LocalContext.current
                val activity = context as? Activity // Safe cast

                if (activity == null) {
                    Log.e("MainActivity", "Error: Context is not an Activity.")
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Critical Error: Cannot get Activity context.")
                    }
                } else {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(
                            viewModel = viewModel,
                            activity = activity,
                            onNavigateUp = { showSettings = false })
                    } else {
                        MainApp(
                            viewModel = viewModel,
                            activity = activity,
                            onNavigateToSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    activity: Activity,
    onNavigateToSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle() // Collects the new MainScreenState
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            showToast(context, message)
            viewModel.toastMessageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawerContent(
                state = state, // Pass the NEW state object
                onFolderSelected = { folder, account -> // account is generic Account
                    scope.launch { drawerState.close() }
                    // Use internal helper to get IAccount for the temporary selectFolder signature
                    val msalAccount = viewModel.getMsalAccountById(account.id)
                    if (msalAccount != null) {
                        viewModel.selectFolder(folder, msalAccount)
                    } else {
                        Log.e(
                            "MainApp",
                            "Could not find IAccount for selected Account ${account.id}"
                        )
                        showToast(context, "Error selecting folder: Account data mismatch.")
                    }
                },
                onSettingsClicked = { scope.launch { drawerState.close() }; onNavigateToSettings() }
            )
        }
    ) {
        Scaffold(
            topBar = {
                val title = state.selectedFolder?.displayName ?: stringResource(R.string.app_name)
                // Use the separate MailTopAppBar composable
                MailTopAppBar(
                    title = title,
                    onNavigationClick = { scope.launch { drawerState.open() } }
                )
            },
            floatingActionButton = { /* Placeholder */ }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                // Read state into local variable for when clause
                val currentAuthState = state.authState

                when (currentAuthState) {
                    is AuthState.Initializing -> {
                        // Use the single LoadingIndicator composable defined below
                        LoadingIndicator(isInitializing = true, isAuthenticating = false)
                    }

                    is AuthState.InitializationError -> {
                        // Use the single AuthInitErrorContent composable defined below
                        AuthInitErrorContent(errorState = currentAuthState)
                    }

                    is AuthState.Initialized -> {
                        when {
                            state.isLoadingAccountAction -> {
                                LoadingIndicator(isInitializing = false, isAuthenticating = true)
                            }

                            state.accounts.isEmpty() -> {
                                // Use the single SignedOutContent composable defined below
                                SignedOutContent(
                                    onAddAccountClick = onNavigateToSettings,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            state.selectedFolder != null -> {
                                val accountForMessages =
                                    state.accounts.find { it.id == state.selectedFolderAccountId }
                                // MessageListContent now correctly receives Account?
                                MessageListContent(
                                    messageDataState = state.messageDataState,
                                    messages = state.messages,
                                    messageError = state.messageError,
                                    accountContext = accountForMessages, // Pass generic Account
                                    onRefresh = { viewModel.refreshMessages(activity) },
                                    onMessageClick = { messageId ->
                                        showToast(
                                            context,
                                            "Clicked: $messageId"
                                        )
                                    }
                                )
                            }

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
                        }
                    }
                } // End when (currentAuthState)
            } // End Box
        } // End Scaffold
    } // End ModalNavigationDrawer
}


// --- Helper Composables (Single Definitions) ---

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

@Composable
private fun SignedOutContent(onAddAccountClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.welcome_message))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.prompt_add_account), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddAccountClick) { Text(stringResource(R.string.manage_accounts_button)) }
        }
    }
}

// showToast remains the same
private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

// Removed duplicated MailTopAppBar - ensure MailTopAppBar.kt exists and has imports