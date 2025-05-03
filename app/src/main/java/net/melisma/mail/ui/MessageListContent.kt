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
import com.microsoft.identity.client.IAccount
import net.melisma.mail.DataState
import net.melisma.mail.Message
import net.melisma.mail.R

/**
 * Displays the main content area for the message list, including pull-to-refresh,
 * loading states, error states, empty states, and the list itself.
 */
@OptIn(ExperimentalMaterial3Api::class) // Required for M3 pull-refresh
@Composable
fun MessageListContent(
    // The current state of message data fetching (e.g., LOADING, SUCCESS, ERROR).
    messageDataState: DataState,
    // The list of messages to display (can be null or empty depending on state).
    messages: List<Message>?,
    // An error message string if messageDataState is ERROR.
    messageError: String?,
    // The account associated with the currently viewed folder, or null for unified views.
    accountContext: IAccount?,
    // Callback invoked when the user pulls to refresh.
    onRefresh: () -> Unit,
    // Callback invoked when a message item is clicked.
    onMessageClick: (String) -> Unit
) {
    // Determines if the pull-to-refresh indicator should be shown.
    val isRefreshing = messageDataState == DataState.LOADING

    // Use a Column to place the optional account header above the refreshable list.
    Column(modifier = Modifier.fillMaxSize()) {

        // --- Account Context Header ---
        // Only show the account header if there is a specific account context
        // (i.e., not viewing a unified inbox).
        if (accountContext != null) {
            AccountContextHeader(account = accountContext)
            HorizontalDivider() // Divider between header and list.
        }
        // --- End Account Context Header ---

        // Provides pull-to-refresh functionality around the message list area.
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                // Ensures the refresh box takes up the remaining vertical space.
                .weight(1f)
        ) {
            // Display content based on the message data state.
            when (messageDataState) {
                // Initial state often means loading has just started or no folder is selected.
                DataState.INITIAL -> {
                    // If messages are null, typically means loading or nothing selected yet.
                    // If messages are non-null, display stale data while loading.
                    if (messages != null) {
                        MessageListSuccessContent(messages, onMessageClick)
                    } else {
                        // Show a generic prompt if no folder is selected (handled by MainActivity logic mostly).
                        // If loading, the PullToRefreshBox shows the indicator.
                        FullScreenMessage(
                            icon = null,
                            iconContentDescription = null,
                            title = stringResource(R.string.select_a_folder)
                        )
                    }
                }

                // Loading state: PullToRefreshBox shows indicator. Display stale data if available.
                DataState.LOADING -> {
                    if (messages != null) {
                        // Show previous list content while loading indicator is visible at the top.
                        MessageListSuccessContent(messages, onMessageClick)
                    } else {
                        // Keep content area blank while loading indicator shows at top if no stale data.
                        Spacer(Modifier.fillMaxSize())
                    }
                }

                // Error state: Display a full-screen error message.
                DataState.ERROR -> {
                    FullScreenMessage(
                        icon = Icons.Filled.CloudOff,
                        iconContentDescription = stringResource(R.string.cd_error_loading_messages),
                        title = stringResource(R.string.error_loading_messages_title),
                        message = messageError ?: stringResource(R.string.error_unknown_occurred)
                        // Retry is implicitly handled by the pull-to-refresh gesture.
                    )
                }

                // Success state: Display the message list or an empty state message.
                DataState.SUCCESS -> {
                    if (messages.isNullOrEmpty()) {
                        // Show message indicating the folder is empty.
                        FullScreenMessage(
                            icon = Icons.Filled.Email,
                            iconContentDescription = stringResource(R.string.cd_no_messages),
                            title = stringResource(R.string.no_messages_title),
                            message = stringResource(R.string.folder_is_empty)
                        )
                    } else {
                        // Display the actual list of messages.
                        MessageListSuccessContent(messages, onMessageClick)
                    }
                }
            } // End when (messageDataState)
        } // End PullToRefreshBox
    } // End Column wrapper
}

/**
 * Displays the list of messages using a LazyColumn when the data state is SUCCESS
 * and the list is not empty.
 */
@Composable
private fun MessageListSuccessContent(
    messages: List<Message>,
    onMessageClick: (String) -> Unit
) {
    // Efficiently displays a potentially long list of messages.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Render each message item using the MessageListItem composable.
        // Use message ID as the stable key for better performance and state preservation.
        items(messages, key = { it.id }) { message ->
            MessageListItem(
                message = message,
                onClick = { onMessageClick(message.id) }
            )
            // Add a thin divider between messages.
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

/**
 * Reusable composable for displaying full-screen messages, used for
 * loading prompts, error messages, and empty states.
 */
@Composable
private fun FullScreenMessage(
    icon: ImageVector?,
    iconContentDescription: String?,
    title: String,
    message: String? = null,
    // Optional slot for additional content like buttons.
    content: (@Composable () -> Unit)? = null
) {
    // Centers the content within the available space.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Arranges icon, title, message, and optional content vertically.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Display icon if provided.
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant // Use a muted color.
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            // Display the main title text.
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            // Display the secondary message text if provided.
            if (message != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant // Use a muted color.
                )
            }
            // Display additional content (e.g., buttons) if provided.
            if (content != null) {
                Spacer(modifier = Modifier.height(24.dp))
                content()
            }
        }
    }
}

/**
 * Displays a small header indicating the account context for the message list.
 * Removed the "Account: " prefix for a cleaner look.
 */
@Composable
private fun AccountContextHeader(account: IAccount) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp), // Standard padding.
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Display the username directly. Fallback to "Unknown User" if username is null.
        Text(
            text = account.username ?: stringResource(R.string.unknown_user),
            style = MaterialTheme.typography.labelMedium, // Use a smaller label style.
            color = MaterialTheme.colorScheme.onSurfaceVariant // Use a subdued color.
        )
    }
}