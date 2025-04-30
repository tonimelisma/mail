package net.melisma.mail

// Import composables from the new ui package
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.SignedOutContent
import net.melisma.mail.ui.theme.MailTheme

class MainActivity : ComponentActivity() {

    private val microsoftAuthManager: MicrosoftAuthManager by lazy {
        MicrosoftAuthManager(
            context = applicationContext,
            configResId = R.raw.auth_config
        )
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.provideFactory(microsoftAuthManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MailTheme {
                val activity = LocalContext.current as Activity
                MainApp(viewModel = viewModel, activity = activity)
            }
        }
    }
}

// Main Application Composable with Scaffold and Navigation Logic
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel, activity: Activity) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Show toast messages
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            showToast(context, message)
            viewModel.toastMessageShown()
        }
    }

    // Initial Load Logic
    LaunchedEffect(state.currentAccount, state.folders, state.isLoadingFolders) {
        if (state.currentAccount != null && state.folders == null && !state.isLoadingFolders) {
            Log.d("MainApp", "Triggering initial folder fetch.")
            viewModel.refreshFolders(activity)
        }
    }
    LaunchedEffect(state.folders, state.selectedFolder) {
        val folders = state.folders
        if (!folders.isNullOrEmpty() && state.selectedFolder == null) {
            val inboxFolder = folders.find { it.displayName.equals("Inbox", ignoreCase = true) }
            if (inboxFolder != null) {
                Log.d("MainApp", "Folders loaded and none selected, selecting Inbox.")
                viewModel.selectFolder(inboxFolder, activity)
            } else {
                Log.w("MainApp", "Folders loaded, but Inbox not found. Selecting first folder.")
                // Avoid potential crash if folders is empty after filtering somehow
                if (folders.isNotEmpty()) {
                    viewModel.selectFolder(folders.first(), activity)
                }
            }
        }
    }

    // Main UI Structure
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawerContent(
                state = state,
                onFolderSelected = { folder ->
                    scope.launch { drawerState.close() }
                    viewModel.selectFolder(folder, activity)
                },
                onSignOutClick = {
                    scope.launch { drawerState.close() }
                    viewModel.signOut(activity)
                },
                onRefreshFolders = {
                    viewModel.refreshFolders(activity)
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                MailTopAppBar(
                    title = if (state.selectedFolder != null && state.messages == null && state.isLoadingMessages) {
                        "Loading..."
                    } else {
                        state.selectedFolder?.displayName ?: "Mail"
                    },
                    account = state.currentAccount,
                    onNavigationClick = { scope.launch { drawerState.open() } }
                )
            }
            // floatingActionButton = { /* TODO: Add Compose FAB */ }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                // Conditional content based on auth and loading state
                when {
                    // Signed Out
                    state.currentAccount == null && !state.isLoadingAuthAction && state.isAuthInitialized -> {
                        SignedOutContent(
                            isAuthInitialized = state.isAuthInitialized,
                            authInitializationError = state.authInitializationError,
                            onSignInClick = { viewModel.signIn(activity) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Loading Auth Action or Initializing Auth
                    state.isLoadingAuthAction || (!state.isAuthInitialized && state.authInitializationError == null && state.currentAccount == null) -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                            if (!state.isAuthInitialized && state.authInitializationError == null) {
                                Text(
                                    "Initializing Auth...",
                                    modifier = Modifier.padding(top = 60.dp)
                                )
                            }
                        }
                    }
                    // Signed In
                    state.currentAccount != null -> {
                        MessageListContent(
                            isLoading = state.isLoadingMessages,
                            messages = state.messages,
                            error = state.messageError,
                            onRefresh = { viewModel.refreshMessages(activity) },
                            onMessageClick = { messageId ->
                                showToast(context, "Clicked message ID: $messageId")
                                // TODO: Navigate to message detail view
                            }
                        )
                    }
                    // Auth Init Error
                    state.authInitializationError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Error initializing authentication: ${state.authInitializationError?.message}",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}


// Helper function to get Activity context from Composable (keep private here)
@Composable
private fun findActivity(): Activity {
    val context = LocalContext.current
    return context as? Activity
        ?: throw IllegalStateException("Composable is not hosted in an Activity context")
}

// Helper function for displaying Toast messages (keep private here)
private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

// --- Previews ---
// Previews for the main app structure are difficult due to dependencies.
// Keep previews within the individual extracted UI files.