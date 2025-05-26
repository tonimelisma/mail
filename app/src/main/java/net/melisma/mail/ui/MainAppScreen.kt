package net.melisma.mail.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.melisma.mail.MainViewModel
import net.melisma.mail.R
import net.melisma.mail.ViewMode
import net.melisma.mail.navigation.AppRoutes

// Helper functions that were originally in MainActivity or might be needed
// (Consider moving to a Util file if they become more general)
fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

@Composable
fun LoadingIndicator(statusText: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    navController: NavHostController,
    mainViewModel: MainViewModel
) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val TAG_COMPOSABLE = "MainAppScreenComposable"

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Log.d(TAG_COMPOSABLE, "LaunchedEffect: Showing toast message: $it")
            showToast(context, it)
            Log.d(TAG_COMPOSABLE, "LaunchedEffect: Notifying ViewModel that toast was shown")
            mainViewModel.toastMessageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawerContent(
                state = state,
                onFolderSelected = { folder, account ->
                    scope.launch { drawerState.close() }
                    mainViewModel.selectFolder(folder, account)
                },
                onSettingsClicked = {
                    scope.launch { drawerState.close() }
                    navController.navigate(AppRoutes.SETTINGS)
                },
                onRefreshFolders = { accountId ->
                    mainViewModel.refreshFoldersForAccount(
                        accountId,
                        context as? Activity
                    )
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
                        // Actions for TopAppBar can be added here if needed
                    }
                )
            },
            floatingActionButton = { /* Placeholder for FAB */ }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                val currentAuthState = state.overallApplicationAuthState
                Log.d(
                    TAG_COMPOSABLE,
                    "MainAppScreen content recomposing. AuthState: ${currentAuthState::class.simpleName}, isLoadingAccountAction: ${state.isLoadingAccountAction}, Accounts: ${state.accounts.size}, SelectedFolder: ${state.selectedFolder?.displayName}"
                )

                when (currentAuthState) {
                    OverallApplicationAuthState.UNKNOWN -> {
                        LoadingIndicator(statusText = stringResource(R.string.status_initializing_auth))
                    }

                    OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED -> {
                        if (state.isLoadingAccountAction) {
                            LoadingIndicator(statusText = stringResource(R.string.status_authenticating))
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("No accounts configured. Please add an account.")
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { navController.navigate(AppRoutes.SETTINGS) }) {
                                    Text("Go to Settings")
                                }
                            }
                        }
                    }

                    OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Please re-authenticate your account(s) via Settings.")
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { navController.navigate(AppRoutes.SETTINGS) }) {
                                Text("Go to Settings")
                            }
                            state.accounts.find { it.needsReauthentication }?.let { acc ->
                                Button(onClick = {
                                    mainViewModel.initiateSignIn(
                                        acc.providerType,
                                        context as Activity
                                    )
                                }) {
                                    Text("Re-authenticate ${acc.username}")
                                }
                            }
                        }
                    }

                    OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION,
                    OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED -> {
                        if (state.selectedFolder == null && !state.isAnyFolderLoading && state.accounts.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (state.isAnyFolderLoading) {
                                    LoadingIndicator("Loading folders...")
                                } else {
                                    Text("Please select a folder from the drawer.")
                                }
                            }
                        } else if (state.selectedFolder != null) {
                            val selectedAccount = mainViewModel.getSelectedAccount()
                            val isViewModelRefreshing = when (state.currentViewMode) {
                                ViewMode.MESSAGES -> state.isMessageLoading
                                ViewMode.THREADS -> state.threadDataState is net.melisma.core_data.model.ThreadDataState.Loading
                            }

                            PullToRefreshBox(
                                isRefreshing = isViewModelRefreshing,
                                onRefresh = {
                                    when (state.currentViewMode) {
                                        ViewMode.MESSAGES -> mainViewModel.retryFetchMessagesForCurrentFolder()
                                        ViewMode.THREADS -> mainViewModel.retryFetchThreadsForCurrentFolder()
                                    }
                                }
                            ) {
                                when (state.currentViewMode) {
                                    ViewMode.MESSAGES -> {
                                        MessageListContent(
                                            messages = state.messages,
                                            isLoading = state.isMessageLoading,
                                            error = state.messageError,
                                            accountContext = selectedAccount,
                                            onMessageClick = { messageId ->
                                                Log.d(
                                                    TAG_COMPOSABLE,
                                                    "MessageListContent clicked. Message ID: $messageId, Account ID: ${selectedAccount?.id}"
                                                )
                                                val accountIdToUse = selectedAccount?.id
                                                if (accountIdToUse != null && accountIdToUse.isNotBlank()) {
                                                    navController.navigate(
                                                        AppRoutes.messageDetailPath(
                                                            accountId = accountIdToUse,
                                                            messageId = messageId
                                                        )
                                                    )
                                                } else {
                                                    Log.e(
                                                        TAG_COMPOSABLE,
                                                        "Clicked message's accountId is blank or selectedAccount is null. Cannot navigate to message detail."
                                                    )
                                                    showToast(
                                                        context,
                                                        "Error: Account context missing for message."
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    ViewMode.THREADS -> {
                                        ThreadListContent(
                                            threadDataState = state.threadDataState,
                                            accountContext = selectedAccount,
                                            onThreadClick = { threadId ->
                                                val accountIdToUse = state.selectedFolderAccountId
                                                if (accountIdToUse != null) {
                                                    Log.d(
                                                        TAG_COMPOSABLE,
                                                        "Thread clicked: $threadId from account $accountIdToUse"
                                                    )
                                                    showToast(
                                                        context,
                                                        "Thread view not yet implemented."
                                                    )
                                                } else {
                                                    Log.e(
                                                        TAG_COMPOSABLE,
                                                        "selectedFolderAccountId is null, cannot handle thread click."
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } else if (state.isAnyFolderLoading || state.isLoadingAccountAction) {
                            LoadingIndicator(statusText = if (state.isLoadingAccountAction) "Loading accounts..." else "Loading folders...")
                        } else {
                            Text(
                                "No folder selected or content available.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
} 