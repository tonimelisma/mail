package net.melisma.mail

// Ensure this import exists if needed elsewhere, but not for @SuppressLint on MessageListContent now
// import android.annotation.SuppressLint
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel, activity: Activity) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            showToast(context, message)
            viewModel.toastMessageShown()
        }
    }

    // Initial Load Logic (with Smart Cast Fixes)
    LaunchedEffect(state.currentAccount, state.folderDataState) {
        val account = state.currentAccount // Local val for smart cast
        if (account != null && state.folderDataState == DataState.INITIAL) {
            Log.d("MainApp", "Triggering initial folder fetch.")
            viewModel.refreshFolders(activity)
        }
    }
    LaunchedEffect(state.folders, state.selectedFolder, state.folderDataState) {
        val currentFolders = state.folders // Local val for smart cast
        val currentSelectedFolder = state.selectedFolder // Local val for smart cast
        if (state.folderDataState == DataState.SUCCESS && !currentFolders.isNullOrEmpty() && currentSelectedFolder == null) {
            val inboxFolder =
                currentFolders.find { it.displayName.equals("Inbox", ignoreCase = true) }
            if (inboxFolder != null) {
                Log.d("MainApp", "Folders loaded and none selected, selecting Inbox.")
                viewModel.selectFolder(inboxFolder, activity)
            } else {
                Log.w("MainApp", "Folders loaded, but Inbox not found. Selecting first folder.")
                if (currentFolders.isNotEmpty()) { // Use local val
                    viewModel.selectFolder(currentFolders.first(), activity)
                }
            }
        }
    }

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
                // Use local variable for selectedFolder in title logic
                val currentSelectedFolder = state.selectedFolder
                MailTopAppBar(
                    title = when {
                        currentSelectedFolder == null && state.folderDataState == DataState.LOADING -> "Loading Folders..."
                        currentSelectedFolder == null -> "Mail" // Default before selection
                        state.messageDataState == DataState.LOADING && state.messages == null -> "Loading..." // Initial message load
                        else -> currentSelectedFolder.displayName // Use local variable
                    },
                    account = state.currentAccount,
                    onNavigationClick = { scope.launch { drawerState.open() } }
                )
            }
            // floatingActionButton = { ... }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                val account = state.currentAccount
                when {
                    account == null && !state.isLoadingAuthAction && state.isAuthInitialized -> {
                        SignedOutContent(
                            isAuthInitialized = state.isAuthInitialized,
                            authInitializationError = state.authInitializationError,
                            onSignInClick = { viewModel.signIn(activity) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    state.isLoadingAuthAction || (!state.isAuthInitialized && state.authInitializationError == null && account == null) -> {
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

                    account != null -> {
                        MessageListContent(
                            dataState = state.messageDataState,
                            messages = state.messages,
                            error = state.messageError,
                            onRefresh = { viewModel.refreshMessages(activity) },
                            onMessageClick = { messageId ->
                                showToast(
                                    context,
                                    "Clicked: $messageId"
                                )
                            }
                        )
                    }
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


// Keep private helper functions in MainActivity
@Composable
private fun findActivity(): Activity {
    val context = LocalContext.current
    return context as? Activity
        ?: throw IllegalStateException("Composable is not hosted in an Activity context")
}

private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

// --- Previews --- (Should be in individual UI files)