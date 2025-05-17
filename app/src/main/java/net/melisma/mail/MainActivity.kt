// File: app/src/main/java/net/melisma/mail/MainActivity.kt
package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.ThreadListContent
import net.melisma.mail.ui.settings.SettingsScreen
import net.melisma.mail.ui.theme.MailTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val TAG = "MainActivity_AppAuth"

    private lateinit var appAuthLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() called")
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Setting up appAuthLauncher for AppAuth Intent results")
        appAuthLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.i(TAG, "appAuthLauncher: Result received. ResultCode: ${result.resultCode}")
            viewModel.handleGoogleAppAuthResult(this, result.resultCode, result.data)
        }

        Log.d(TAG, "Setting up observation of appAuthIntentToLaunch flow from ViewModel")
        lifecycleScope.launch {
            viewModel.appAuthIntentToLaunch.collect { intent ->
                intent?.let {
                    Log.i(
                        TAG,
                        "Received AppAuth Intent from ViewModel. Action: ${it.action}, Data: ${it.dataString}"
                    )
                    try {
                        Log.d(TAG, "Launching AppAuth Intent with appAuthLauncher.")
                        appAuthLauncher.launch(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching AppAuth Intent via appAuthLauncher", e)
                        val errorPrefix = getString(R.string.error_google_signin_failed_generic)
                        Toast.makeText(
                            this@MainActivity,
                            "$errorPrefix: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        Log.d(TAG, "Setting up UI with enableEdgeToEdge and content")
        enableEdgeToEdge()
        setContent {
            Log.d(TAG, "Content composition started")
            MailTheme {
                val context = LocalContext.current
                var showSettings by remember { mutableStateOf(false) }
                Log.d(TAG, "Initial showSettings state: $showSettings")

                if (showSettings) {
                    Log.d(TAG, "Showing SettingsScreen")
                    val activity = context as? Activity
                    if (activity != null) {
                        Log.d(TAG, "Context successfully cast to Activity for SettingsScreen")
                        SettingsScreen(
                            viewModel = viewModel,
                            activity = activity,
                            onNavigateUp = {
                                Log.d(TAG, "Navigation: Settings -> Main App")
                                showSettings = false
                            }
                        )
                    } else {
                        Log.e(TAG, "Error: Context is not an Activity in Settings path")
                        ErrorDisplay(stringResource(R.string.error_critical_no_activity_context))
                    }
                } else {
                    Log.d(TAG, "Showing MainApp")
                    val activity = context as? Activity
                    if (activity != null) {
                        Log.d(TAG, "Context successfully cast to Activity for MainApp")
                        MainApp(
                            viewModel = viewModel,
                            activity = activity,
                            onNavigateToSettings = {
                                Log.d(TAG, "Navigation: Main App -> Settings")
                                showSettings = true
                            }
                        )
                    } else {
                        Log.e(TAG, "Error: Context is not an Activity in MainApp path")
                        ErrorDisplay(stringResource(R.string.error_critical_no_activity_context))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(
            TAG,
            "onNewIntent: Intent received. Action: ${intent.action}, Data: ${intent.dataString}, Flags: ${intent.flags}"
        )
    }

    @Composable
    fun ErrorDisplay(message: String) {
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val TAG_COMPOSABLE = "MainAppComposable"

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            Log.d(TAG_COMPOSABLE, "LaunchedEffect: Showing toast message: $message")
            showToast(context, message)
            Log.d(TAG_COMPOSABLE, "LaunchedEffect: Notifying ViewModel that toast was shown")
            viewModel.toastMessageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawerContent(
                state = state,
                onFolderSelected = { folder, account ->
                    scope.launch { drawerState.close() }
                    viewModel.selectFolder(folder, account)
                },
                onSettingsClicked = {
                    scope.launch { drawerState.close() }
                    onNavigateToSettings()
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                val title = state.selectedFolder?.displayName ?: stringResource(R.string.app_name)
                MailTopAppBar(
                    title = title,
                    onNavigationClick = { scope.launch { drawerState.open() } },
                    actions = {
                        // IconButton(onClick = { viewModel.toggleViewMode() }) { // <-- REMOVE THIS BLOCK
                        //     Icon(
                        //         imageVector = if (state.currentViewMode == ViewMode.THREADS) Icons.AutoMirrored.Filled.List else Icons.Filled.Forum,
                        //         contentDescription = if (state.currentViewMode == ViewMode.THREADS) "Switch to Message View" else "Switch to Thread View"
                        //     )
                        // }
                    }
                )
            },
            floatingActionButton = { /* Placeholder */ }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                val currentAuthState = state.authState
                Log.d(
                    TAG_COMPOSABLE,
                    "MainApp content recomposing. AuthState: ${currentAuthState::class.simpleName}, isLoadingAccountAction: ${state.isLoadingAccountAction}, Accounts: ${state.accounts.size}, SelectedFolder: ${state.selectedFolder?.displayName}"
                )

                when (currentAuthState) {
                    is AuthState.Initializing -> {
                        LoadingIndicator(statusText = stringResource(R.string.status_initializing_auth))
                    }
                    is AuthState.AuthError -> {
                        AuthErrorContent(errorState = currentAuthState)
                    }

                    is AuthState.SignedOut -> {
                        if (state.isLoadingAccountAction) {
                            LoadingIndicator(statusText = stringResource(R.string.status_authenticating))
                        } else {
                            SignedOutContent(
                                onAddAccountClick = onNavigateToSettings,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    is AuthState.SignedInWithMicrosoft,
                    is AuthState.SignedInWithGoogle,
                    is AuthState.SignedInBoth -> {
                        if (state.isLoadingAccountAction && state.selectedFolder == null) {
                            LoadingIndicator(statusText = stringResource(R.string.status_loading_accounts))
                        } else if (state.accounts.isEmpty() && !state.isLoadingAccountAction) {
                            SignedOutContent(
                                onAddAccountClick = onNavigateToSettings,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (state.selectedFolder != null) {
                            val accountForMessages =
                                state.accounts.find { it.id == state.selectedFolderAccountId }
                            val currentActivity = LocalActivity.current
                            if (currentActivity != null) {
                                MailContentWrapper(
                                    viewModel = viewModel,
                                    state = state,
                                    activity = currentActivity,
                                    accountContext = accountForMessages
                                )
                            } else {
                                LoadingIndicator(statusText = "Waiting for activity...")
                            }
                        } else {
                            NoFolderSelectedContent(onDrawerOpen = { scope.launch { drawerState.open() } })
                        }
                    }
                }
            }
        }
    }
}

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

@Composable
private fun AuthErrorContent(errorState: AuthState.AuthError) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text(
            stringResource(R.string.title_authentication_error),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(errorState.message)
    }
}

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

@Composable
fun NoFolderSelectedContent(onDrawerOpen: () -> Unit) {
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.prompt_select_folder),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MailContentWrapper(
    viewModel: MainViewModel,
    state: MainScreenState,
    activity: Activity,
    accountContext: Account?
) {
    val TAG_MAIL_CONTENT = "MailContentWrapper"
    when (state.currentViewMode) {
        ViewMode.THREADS -> {
            Log.d(TAG_MAIL_CONTENT, "Displaying ThreadListContent")
            ThreadListContent(
                threadDataState = state.threadDataState,
                accountContext = accountContext,
                onThreadClick = { threadId ->
                    Log.d(
                        TAG_MAIL_CONTENT,
                        "Thread clicked: $threadId - Navigation to thread detail not implemented yet."
                    )
                    // TODO: Navigate to thread detail screen
                    // viewModel.selectThread(threadId)
                }
            )
        }

        ViewMode.MESSAGES -> {
            Log.d(TAG_MAIL_CONTENT, "Displaying MessageListContent")
            MessageListContent(
                messageDataState = state.messageDataState,
                accountContext = accountContext,
                onMessageClick = { messageId ->
                    Log.d(
                        TAG_MAIL_CONTENT,
                        "Message clicked: $messageId - Navigation to message detail not implemented yet."
                    )
                    // TODO: Navigate to message detail screen
                    // viewModel.selectMessage(messageId)
                }
            )
        }
    }
}

private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) {
        Log.d("MainActivityToast", "showToast: Message is null or blank, not showing toast")
        return
    }
    Log.d("MainActivityToast", "showToast: Displaying toast message: $message")
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
