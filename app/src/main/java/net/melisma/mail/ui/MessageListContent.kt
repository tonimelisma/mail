// File: app/src/main/java/net/melisma/mail/ui/MessageListContent.kt
// Updated to use MessageDataState

package net.melisma.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Message
import net.melisma.mail.R

/**
 * Composable responsible for displaying the list of messages or status indicators
 * (loading, error, empty) based on the provided state. Includes pull-to-refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListContent(
    messages: LazyPagingItems<Message>, // Changed to LazyPagingItems
    // isLoading: Boolean,           // Derived from messages.loadState
    // error: String?,               // Derived from messages.loadState
    accountContext: Account?,
    onMessageClick: (String) -> Unit,
    onRetry: () -> Unit, // For retry button on error
    onRefresh: () -> Unit // For pull to refresh
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (accountContext != null) {
            AccountContextHeader(account = accountContext)
            HorizontalDivider()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            // Handle combined Paging LoadStates
            val loadState = messages.loadState
            val isInitialLoading = loadState.refresh is LoadState.Loading
            val isAppending = loadState.append is LoadState.Loading
            val initialLoadError = loadState.refresh as? LoadState.Error
            val appendError = loadState.append as? LoadState.Error

            when {
                isInitialLoading && messages.itemCount == 0 -> {
                    LoadingIndicator(statusText = stringResource(R.string.title_loading_messages))
                }

                initialLoadError != null && messages.itemCount == 0 -> {
                    FullScreenMessage(
                        icon = Icons.Filled.CloudOff,
                        iconContentDescription = stringResource(R.string.cd_error_loading_messages),
                        title = stringResource(R.string.error_loading_messages_title),
                        message = initialLoadError.error.localizedMessage
                            ?: stringResource(R.string.unknown_error),
                        content = {
                            ButtonPrimary(
                                text = stringResource(id = R.string.action_retry),
                                onClick = onRetry
                            )
                        }
                    )
                }
                // No error, but list is empty after initial load/refresh finished
                !isInitialLoading && messages.itemCount == 0 -> {
                    FullScreenMessage(
                        icon = Icons.Filled.Email,
                        iconContentDescription = stringResource(R.string.cd_no_messages),
                        title = stringResource(R.string.no_messages_title),
                        message = stringResource(R.string.folder_is_empty)
                    )
                }
                // List has items, or is loading more items
                else -> {
                    MessageListSuccessContentPaging(
                        messages = messages,
                        onMessageClick = onMessageClick,
                        isAppending = isAppending,
                        appendError = appendError?.error?.localizedMessage
                    )
                }
            }
        }
    }
}

/** Displays the actual list of messages using LazyColumn with PagingItems. */
@Composable
private fun MessageListSuccessContentPaging(
    messages: LazyPagingItems<Message>,
    onMessageClick: (String) -> Unit,
    isAppending: Boolean,
    appendError: String?
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            count = messages.itemCount,
            key = messages.itemKey { it.id } // Use itemKey for stable keys
        ) { index ->
            val message = messages[index]
            if (message != null) {
                MessageListItem(message = message, onClick = { onMessageClick(message.id) })
                HorizontalDivider(thickness = 0.5.dp)
            }
        }

        // Append loading state
        if (isAppending) {
            item {
                LoadingIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    statusText = stringResource(id = R.string.loading_more_messages)
                )
            }
        }

        // Append error state
        if (appendError != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_loading_more_failed, appendError),
                        color = MaterialTheme.colorScheme.error
                    )
                    // Consider adding a retry for append failures if appropriate
                }
            }
        }
    }
}

// --- Helper Composables (Single Definitions) ---

/** Displays a centered message, typically used for error or empty states. */
@Composable
internal fun FullScreenMessage(
    icon: ImageVector?,
    iconContentDescription: String?,
    title: String,
    message: String? = null,
    content: (@Composable () -> Unit)? = null // Optional slot for extra content like buttons
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Display icon if provided
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant // Use subtle color
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            // Display the main title text
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            // Display optional sub-message text
            if (message != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Display optional extra content (e.g., a retry button)
            if (content != null) {
                Spacer(modifier = Modifier.height(24.dp))
                content()
            }
        }
    }
}

/** Displays a simple header showing the username of the current account context. */
@Composable
internal fun AccountContextHeader(account: Account) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                R.string.account_context_label,
                account.username
            ), // Use formatted string
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
