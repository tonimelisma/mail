package net.melisma.mail

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.launch
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.ui.MailDrawerContent
import net.melisma.mail.ui.MailTopAppBar
import net.melisma.mail.ui.MessageListContent
import net.melisma.mail.ui.theme.MailTheme

// SignedOutContent is now defined within this file

class MainActivity : ComponentActivity() {

    private val microsoftAuthManager: MicrosoftAuthManager by lazy {
        MicrosoftAuthManager(
            context = applicationContext,
            configResId = R.raw.auth_config
        )
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.provideFactory(applicationContext, microsoftAuthManager)
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

    // Initial Load Logic
    LaunchedEffect(state.currentAccount, state.folderDataState) {
        val account = state.currentAccount
        if (account != null && state.folderDataState == DataState.INITIAL) {
            Log.d("MainApp", "Triggering initial folder fetch.")
            viewModel.refreshFolders(activity)
        }
    }
    LaunchedEffect(state.folders, state.selectedFolder, state.folderDataState) {
        val currentFolders = state.folders
        val currentSelectedFolder = state.selectedFolder
        if (state.folderDataState == DataState.SUCCESS && !currentFolders.isNullOrEmpty() && currentSelectedFolder == null) {
            val inboxFolder =
                currentFolders.find { it.displayName.equals("Inbox", ignoreCase = true) }
            if (inboxFolder != null) {
                Log.d("MainApp", "Folders loaded and none selected, selecting Inbox.")
                viewModel.selectFolder(inboxFolder, activity)
            } else {
                Log.w("MainApp", "Folders loaded, but Inbox not found. Selecting first folder.")
                if (currentFolders.isNotEmpty()) {
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
                val currentSelectedFolder = state.selectedFolder
                MailTopAppBar(
                    title = when {
                        state.folderDataState == DataState.LOADING -> "Loading Folders..."
                        state.folderDataState == DataState.ERROR -> "Mail"
                        currentSelectedFolder == null -> "Mail"
                        state.messageDataState == DataState.LOADING && state.messages == null -> "Loading..."
                        else -> currentSelectedFolder.displayName
                    },
                    account = state.currentAccount,
                    onNavigationClick = { scope.launch { drawerState.open() } }
                )
            },
            floatingActionButton = {
                // Placeholder for Compose FAB
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                val account = state.currentAccount // Use local val for checks

                // Restructured when block
                when {
                    // Handle Loading States First
                    state.isLoadingAuthAction || (!state.isAuthInitialized && state.authInitializationError == null) -> {
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
                    // Handle Auth Init Error State
                    state.authErrorUserMessage != null && !state.isAuthInitialized -> { // Show auth init error only if not initialized successfully
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Authentication Error:\n${state.authErrorUserMessage}",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    // Handle Signed In State
                    account != null -> {
                        MessageListContent(
                            folderDataState = state.folderDataState,
                            folderError = state.folderError,
                            messageDataState = state.messageDataState,
                            messages = state.messages,
                            messageError = state.messageError,
                            onRefresh = { viewModel.refreshMessages(activity) },
                            onMessageClick = { messageId ->
                                showToast(
                                    context,
                                    "Clicked: $messageId"
                                )
                            }
                        )
                    }
                    // Handle Signed Out State (The only remaining possibility is account == null and no errors/loading)
                    else -> {
                        SignedOutContent(
                            isAuthInitialized = state.isAuthInitialized, // Should be true here if no error
                            authInitializationError = state.authInitializationError, // Likely null here
                            authErrorUserMessage = state.authErrorUserMessage, // Likely null here
                            onSignInClick = { viewModel.signIn(activity) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } // End when
            } // End Box
        } // End Scaffold
    } // End ModalNavigationDrawer
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

// SignedOutContent defined here needs MsalException import
@Composable
fun SignedOutContent(
    isAuthInitialized: Boolean,
    authInitializationError: MsalException?, // Raw error type
    authErrorUserMessage: String?, // Mapped user-friendly message
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome to Melisma Mail")
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onSignInClick,
                // Only enable if initialized AND there isn't a persistent auth init error shown
                enabled = isAuthInitialized // && authErrorUserMessage == null
            ) {
                Text("Sign In with Microsoft")
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Show initialization status or error
            if (!isAuthInitialized && authInitializationError == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("(Initializing Auth...)", style = MaterialTheme.typography.bodySmall)
                }
                // Use the mapped user message if available for auth init errors
            } else if (authErrorUserMessage != null && !isAuthInitialized) { // Check !isAuthInitialized to distinguish from transient errors after sign-in
                Text(
                    "(Authentication Failed: $authErrorUserMessage)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            // Fallback to raw error message only if mapping failed and not initialized
            else if (authInitializationError != null && !isAuthInitialized) {
                Text(
                    "(Authentication Failed: ${authInitializationError.message ?: "Unknown error"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

// --- Previews --- (Should be in respective UI files)