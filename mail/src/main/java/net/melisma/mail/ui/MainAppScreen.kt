package net.melisma.mail.ui

import android.app.Activity
import android.content.Context
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.melisma.mail.MainViewModel
import net.melisma.mail.R
import net.melisma.mail.ViewMode
import net.melisma.mail.navigation.AppRoutes
import net.melisma.mail.ui.sync.SyncErrorSnackbar
import net.melisma.mail.ui.sync.SyncStatusBar
import net.melisma.mail.ui.sync.SyncStatusViewModel
import net.melisma.mail.ui.theme.MailTheme
import timber.log.Timber
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.ThreadDataState
import net.melisma.mail.ui.inbox.MessageListScreen
import net.melisma.mail.ui.inbox.UnifiedInboxScreen

// Helper functions that were originally in MainActivity or might be needed
// (Consider moving to a Util file if they become more general)
fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

@Composable
fun LoadingIndicator(statusText: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
    val messagesPagerFlow by mainViewModel.messagesPagerFlow.collectAsStateWithLifecycle()
    val lazyMessageItems = messagesPagerFlow.collectAsLazyPagingItems()

    var userPulledToRefresh by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Timber.d("LaunchedEffect: Showing toast message: $it")
            showToast(context, it)
            Timber.d("LaunchedEffect: Notifying ViewModel that toast was shown")
            mainViewModel.toastMessageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                MailDrawerContent(
                    state = state,
                    onFolderSelected = { folder, account ->
                        scope.launch { drawerState.close() }
                        mainViewModel.selectFolder(folder, account)
                    },
                    onUnifiedInboxSelected = {
                        scope.launch { drawerState.close() }
                        mainViewModel.selectUnifiedInbox()
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
        }
    ) {
        Scaffold(
            topBar = {
                val title =
                    state.selectedFolder?.displayName ?: stringResource(R.string.app_name)
                MailTopAppBar(
                    title = title,
                    onNavigationClick = { scope.launch { drawerState.open() } },
                    actions = {
                        // Actions for TopAppBar can be added here if needed
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        state.selectedFolderAccountId?.let { accountId ->
                            navController.navigate(AppRoutes.newComposePath(accountId))
                        }
                    }
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.cd_compose)
                    )
                }
            },
            bottomBar = {
                val syncStatusViewModel: SyncStatusViewModel = hiltViewModel()
                val syncStatus by syncStatusViewModel.status.collectAsStateWithLifecycle()
                Column {
                    SyncStatusBar(status = syncStatus)
                    SyncErrorSnackbar(status = syncStatus)
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                val currentAuthState = state.overallApplicationAuthState
                Timber.d(
                    "MainAppScreen recomposing. AuthState: ${currentAuthState::class.simpleName}, isLoadingAccountAction: ${state.isLoadingAccountAction}, Accounts: ${state.accounts.size}, SelectedFolder: ${state.selectedFolder?.displayName}, ViewMode: ${state.currentViewMode}"
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
                                    Text("Re-authenticate ${acc.emailAddress}")
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
                                ViewMode.MESSAGES, ViewMode.UNIFIED_INBOX ->
                                    lazyMessageItems.loadState.refresh is androidx.paging.LoadState.Loading
                                else -> false // THREAD view doesn't use pager
                            }

                            val isSyncingFromServer = state.selectedFolderAccountId?.let { accId ->
                                state.foldersByAccountId[accId] is FolderFetchState.Loading
                            } ?: false

                            val isRefreshing = (isViewModelRefreshing && userPulledToRefresh) || isSyncingFromServer

                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    userPulledToRefresh = true
                                    mainViewModel.onRefresh()
                                }
                            ) {
                                when (state.currentViewMode) {
                                    ViewMode.MESSAGES -> {
                                        MessageListScreen(
                                            lazyMessageItems = lazyMessageItems,
                                            onMessageClick = { message ->
                                                navController.navigate(
                                                    AppRoutes.messageDetailPath(
                                                        message.id,
                                                        message.accountId
                                                    )
                                                )
                                            },
                                            state = state,
                                            mainViewModel = mainViewModel,
                                        )
                                    }

                                    ViewMode.UNIFIED_INBOX -> {
                                        UnifiedInboxScreen(
                                            lazyMessageItems = lazyMessageItems,
                                            onMessageClick = { message ->
                                                navController.navigate(
                                                    AppRoutes.messageDetailPath(
                                                        message.id,
                                                        message.accountId
                                                    )
                                                )
                                            },
                                            state = state,
                                            mainViewModel = mainViewModel,
                                        )
                                    }

                                    ViewMode.THREAD, ViewMode.THREADS -> {
                                        when (val threadState = state.threadDataState) {
                                            is ThreadDataState.Loading -> LoadingIndicator("Loading thread…")
                                            is ThreadDataState.Error -> Text(threadState.error ?: "Error loading thread")
                                            is ThreadDataState.Success -> {
                                                // TODO: Proper thread UI; placeholder for now
                                                Text("Thread view under construction (${threadState.threads.size} messages)")
                                            }
                                            ThreadDataState.Initial -> Text("Select a message to view the thread.")
                                        }
                                    }
                                }
                            }
                        } else {
                            LoadingIndicator(statusText = "Loading...")
                        }
                    }
                }
            }
        }
    }
} 