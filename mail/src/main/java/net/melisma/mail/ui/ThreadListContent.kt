package net.melisma.mail.ui

// import androidx.compose.material3.CircularProgressIndicator // For explicit loading state if PullToRefreshBox isn't enough
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.ThreadDataState
import net.melisma.mail.R

@Composable
fun ThreadListContent(
    threadDataState: ThreadDataState,
    accountContext: Account?,
    onThreadClick: (String) -> Unit
    // isRefreshing and onRefresh are handled by the parent PullToRefreshBox
) {
    Column(modifier = Modifier.fillMaxSize()) {
        accountContext?.let {
            AccountContextHeader(account = it) // Re-use existing if suitable
            HorizontalDivider()
        }

        when (threadDataState) {
            is ThreadDataState.Initial -> {
                FullScreenMessage( // Re-use existing if suitable
                    icon = null,
                    iconContentDescription = null,
                    title = stringResource(R.string.select_a_folder_to_see_threads) // New string resource
                )
            }

            is ThreadDataState.Loading -> {
                // PullToRefreshBox handles the visual indicator.
                // If you want a centered spinner when content is empty:
                // if (threadDataState.threads.isNullOrEmpty()) { // Check if previous success state had items
                //    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                //        CircularProgressIndicator()
                //    }
                // } else {
                //    ShowStaleContentWhileLoading(threads = threadDataState.threads) // Display old data
                // }
                Spacer(Modifier.fillMaxSize()) // Simplest: let PullToRefreshBox show indicator
            }

            is ThreadDataState.Error -> {
                FullScreenMessage(
                    icon = Icons.Filled.CloudOff,
                    iconContentDescription = stringResource(R.string.cd_error_loading_threads), // New string
                    title = stringResource(R.string.error_loading_threads_title), // New string
                    message = threadDataState.error
                )
            }

            is ThreadDataState.Success -> {
                if (threadDataState.threads.isEmpty()) {
                    FullScreenMessage(
                        icon = Icons.Filled.Forum, // Example icon
                        iconContentDescription = stringResource(R.string.cd_no_threads), // New string
                        title = stringResource(R.string.no_threads_title), // New string
                        message = stringResource(R.string.folder_contains_no_threads) // New string
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(threadDataState.threads, key = { it.id }) { mailThread ->
                            ThreadListItem(
                                mailThread = mailThread,
                                onClick = { onThreadClick(mailThread.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
} 