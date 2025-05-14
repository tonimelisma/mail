// File: app/src/main/java/net/melisma/mail/MainActivity.kt
package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.model.ThreadDataState
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.ThreadListContent
import net.melisma.mail.ui.settings.SettingsScreen
import net.melisma.mail.ui.theme.MailTheme
import net.openid.appauth.AuthorizationException

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val TAG = "MainActivity_AppAuth"

    private lateinit var appAuthLauncher: ActivityResultLauncher<Intent>
    private lateinit var legacyGoogleConsentLauncher: ActivityResultLauncher<IntentSenderRequest>


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() called")
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Setting up appAuthLauncher for AppAuth Intent results")
        appAuthLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.i(TAG, "appAuthLauncher: Result received. ResultCode: ${result.resultCode}")
            if (result.resultCode == RESULT_OK) {
                result.data?.let { intent ->
                    Log.d(
                        TAG,
                        "appAuthLauncher: RESULT_OK, intent data is present. Finalizing AppAuth."
                    )
                    viewModel.finalizeGoogleAppAuth(intent)
                } ?: run {
                    Log.e(
                        TAG,
                        "appAuthLauncher: RESULT_OK, but intent data is NULL. This is unexpected for AppAuth success."
                    )
                    viewModel.handleGoogleAppAuthError("Authorization successful, but data missing.")
                }
            } else {
                val exception = AuthorizationException.fromIntent(result.data)
                Log.w(
                    TAG,
                    "appAuthLauncher: AppAuth flow did not return RESULT_OK. ResultCode: ${result.resultCode}, Error: ${exception?.toJsonString() ?: "No exception data"}"
                )
                viewModel.handleGoogleAppAuthError(
                    exception?.errorDescription ?: exception?.error
                    ?: "Authorization cancelled or failed by user."
                )
            }
        }

        Log.d(TAG, "Setting up legacyGoogleConsentLauncher for IntentSender results")
        legacyGoogleConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            Log.d(
                TAG,
                "legacyGoogleConsentLauncher: Result received, resultCode: ${result.resultCode}"
            )
            if (result.resultCode == RESULT_OK) {
                Log.d(
                    TAG,
                    "legacyGoogleConsentLauncher: RESULT_OK. Current UI State Accounts: ${viewModel.uiState.value.accounts.joinToString { it.username }}"
                )
                val account = viewModel.uiState.value.accounts.firstOrNull {
                    it.providerType == "GOOGLE" && viewModel.isAccountPendingGoogleConsent(it.id)
                }
                if (account != null) {
                    Log.d(
                        TAG,
                        "legacyGoogleConsentLauncher: Found Google account: ${account.username} marked as pending consent, finalizing scope consent (legacy path)."
                    )
                    viewModel.finalizeGoogleScopeConsent(account, result.data, this)
                } else {
                    Log.w(
                        TAG,
                        "legacyGoogleConsentLauncher: RESULT_OK but no Google account found pending consent for legacy finalizeGoogleScopeConsent."
                    )
                }
            } else {
                Log.w(
                    TAG,
                    "legacyGoogleConsentLauncher: Flow cancelled or failed. ResultCode: ${result.resultCode}"
                )
            }
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
                        viewModel.clearAppAuthIntentToLaunch()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching AppAuth Intent via appAuthLauncher", e)
                        viewModel.handleGoogleAppAuthError("Failed to launch Google authorization: ${e.message}")
                    }
                }
            }
        }

        Log.d(TAG, "Setting up observation of legacy googleConsentIntentSender flow")
        lifecycleScope.launch {
            viewModel.googleConsentIntentSender.collect { intentSender ->
                if (intentSender != null) {
                    Log.i(
                        TAG,
                        "Received IntentSender for legacy Google consent, launching with legacyGoogleConsentLauncher."
                    )
                    try {
                        val request = IntentSenderRequest.Builder(intentSender).build()
                        Log.d(TAG, "Launching legacy Google consent activity (IntentSender).")
                        legacyGoogleConsentLauncher.launch(request)
                        viewModel.clearGoogleConsentIntentSender()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching legacy Google consent IntentSender", e)
                        Toast.makeText(
                            this@MainActivity,
                            "Error launching Google consent: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearGoogleConsentIntentSender()
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
                        ErrorDisplay("Critical Error: Cannot get Activity context.")
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
                        ErrorDisplay("Critical Error: Cannot get Activity context.")
                    }
                }
            }
        }
    }

    // Corrected onNewIntent signature to match ComponentActivity
    override fun onNewIntent(intent: Intent) { // <<<< CORRECTED to non-nullable Intent
        super.onNewIntent(intent)
        // intent is non-nullable here, so no need for ?.let if directly accessing
        Log.i(
            TAG,
            "onNewIntent: Intent received. Action: ${intent.action}, Data: ${intent.dataString}, Flags: ${intent.flags}"
        )

        // If AppAuth redirects here directly (e.g., due to launchMode="singleTop" for MainActivity
        // AND RedirectUriReceiverActivity is configured to forward or not used),
        // you might need to handle it.
        // Example:
        // if (intent.action == Intent.ACTION_VIEW && intent.dataString?.startsWith(YOUR_APP_SCHEME) == true) {
        //     viewModel.handleAppAuthRedirect(intent) // This would require a new method in ViewModel
        // }
        // For now, the primary redirect handling is assumed to be via the ActivityResultLauncher
        // for the AppAuth intent launched from onCreate's collectors.
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

                    is AuthState.InitializationError -> {
                        AuthInitErrorContent(errorState = currentAuthState)
                    }

                    is AuthState.Initialized -> {
                        when {
                            state.isLoadingAccountAction -> {
                                LoadingIndicator(statusText = stringResource(R.string.status_authenticating))
                            }
                            state.accounts.isEmpty() -> {
                                SignedOutContent(
                                    onAddAccountClick = onNavigateToSettings,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            state.selectedFolder != null -> {
                                val accountForMessages =
                                    state.accounts.find { it.id == state.selectedFolderAccountId }
                                val currentActivity = LocalContext.current as? Activity

                                PullToRefreshBox(
                                    isRefreshing = if (state.currentViewMode == ViewMode.THREADS) {
                                        state.threadDataState is ThreadDataState.Loading && state.threads.isNullOrEmpty()
                                    } else {
                                        state.isMessageLoading && state.messages.isNullOrEmpty()
                                    },
                                    onRefresh = { viewModel.refreshCurrentView(currentActivity) },
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    when (state.currentViewMode) {
                                        ViewMode.THREADS -> {
                                            ThreadListContent(
                                                threadDataState = state.threadDataState,
                                                accountContext = accountForMessages,
                                                onThreadClick = { threadId ->
                                                    showToast(context, "Thread ID: $threadId clicked (Detail view TBD)")
                                                }
                                            )
                                        }
                                        ViewMode.MESSAGES -> {
                                            MessageListContent(
                                                messageDataState = state.messageDataState,
                                                accountContext = accountForMessages,
                                                isRefreshing = state.isMessageLoading,
                                                onRefresh = { viewModel.refreshCurrentView(currentActivity) },
                                                onMessageClick = { messageId ->
                                                    showToast(context, "Message ID: $messageId clicked (Detail view TBD)")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
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

private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) {
        Log.d("MainActivityToast", "showToast: Message is null or blank, not showing toast")
        return
    }
    Log.d("MainActivityToast", "showToast: Displaying toast message: $message")
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
