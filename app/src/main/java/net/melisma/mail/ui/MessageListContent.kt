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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.melisma.mail.Account
import net.melisma.mail.Message
import net.melisma.mail.R
import net.melisma.mail.model.MessageDataState

/**
 * Composable responsible for displaying the list of messages or status indicators
 * (loading, error, empty) based on the provided state. Includes pull-to-refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListContent(
    messageDataState: MessageDataState, // *** Use MessageDataState ***
    // messages: List<Message>?, // Messages are now inside MessageDataState.Success
    // messageError: String?, // Error is now inside MessageDataState.Error
    accountContext: Account?, // Uses generic Account for context header
    isRefreshing: Boolean, // Pass refreshing state for PullToRefreshBox indicator
    onRefresh: () -> Unit,
    onMessageClick: (String) -> Unit
) {
    // val isRefreshing = messageDataState is MessageDataState.Loading // Determine refreshing state

    Column(modifier = Modifier.fillMaxSize()) {
        // Display optional header showing the current account context
        if (accountContext != null) {
            AccountContextHeader(account = accountContext)
            HorizontalDivider()
        }

        // Pull-to-refresh container wrapping the main content
        PullToRefreshBox(
            isRefreshing = isRefreshing, // Control the indicator visibility
            onRefresh = onRefresh,       // Action to perform on pull
            modifier = Modifier
                .fillMaxSize()
                .weight(1f) // Ensure it fills available space in the Column
        ) {
            // Determine content based on the messageDataState
            when (messageDataState) {
                // Initial state before loading or after clearing selection
                is MessageDataState.Initial -> {
                    FullScreenMessage(
                        icon = null, // No icon needed for initial prompt
                        iconContentDescription = null,
                        title = stringResource(R.string.select_a_folder) // Prompt user
                    )
                }
                // Loading state (can happen initially or during refresh)
                is MessageDataState.Loading -> {
                    // Show nothing specific during load, PullToRefreshBox shows indicator
                    // Alternatively, show a centered spinner:
                    // Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    //     CircularProgressIndicator()
                    // }
                    Spacer(Modifier.fillMaxSize()) // Let PullToRefreshBox handle indicator
                }
                // Error state
                is MessageDataState.Error -> {
                    FullScreenMessage(
                        icon = Icons.Filled.CloudOff,
                        iconContentDescription = stringResource(R.string.cd_error_loading_messages),
                        title = stringResource(R.string.error_loading_messages_title),
                        // Get error message from the state object
                        message = messageDataState.error
                    )
                }
                // Success state
                is MessageDataState.Success -> {
                    // Get messages list from the state object
                    val messages = messageDataState.messages
                    if (messages.isEmpty()) {
                        // Show message if folder is empty
                        FullScreenMessage(
                            icon = Icons.Filled.Email,
                            iconContentDescription = stringResource(R.string.cd_no_messages),
                            title = stringResource(R.string.no_messages_title),
                            message = stringResource(R.string.folder_is_empty)
                        )
                    } else {
                        // Display the list of messages
                        MessageListSuccessContent(messages, onMessageClick)
                    }
                }
            } // End when
        } // End PullToRefreshBox
    } // End Column
}

// --- Helper Composables (Single Definitions) ---

/** Displays the actual list of messages using LazyColumn. */
@Composable
private fun MessageListSuccessContent(messages: List<Message>, onMessageClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { message ->
            // Reusable composable for a single message item
            MessageListItem(message = message, onClick = { onMessageClick(message.id) })
            HorizontalDivider(thickness = 0.5.dp) // Separator between items
        }
    }
}

/** Displays a centered message, typically used for error or empty states. */
@Composable
private fun FullScreenMessage(
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
private fun AccountContextHeader(account: Account) {
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
