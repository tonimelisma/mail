package net.melisma.mail.ui

// Removed @SuppressLint if present
// --- Import only necessary M3 Pull To Refresh components ---
// remove import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState // Not needed for basic PullToRefreshBox usage
// --- End Imports ---
// Removed Modifier.nestedScroll import
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.melisma.mail.DataState
import net.melisma.mail.Message

// Content Area: Shows Message List or Loading/Error/Empty states with M3 PullToRefreshBox
@OptIn(ExperimentalMaterial3Api::class) // Required for M3 pull-refresh
@Composable
fun MessageListContent(
    dataState: DataState,
    messages: List<Message>?,
    error: String?, // User-friendly error message
    onRefresh: () -> Unit,
    onMessageClick: (String) -> Unit
) {
    // Determine the refreshing state directly from the overall dataState
    val isRefreshing = dataState == DataState.LOADING

    // Use PullToRefreshBox - it manages the state and indicator internally
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
        // You can customize the indicator via the 'indicator' lambda parameter if needed
        // indicator = { PullToRefreshDefaults.Indicator(isRefreshing = isRefreshing, state = rememberPullToRefreshState() /* or custom state */) }
    ) { // This is the content lambda

        // Display content based on the state
        when (dataState) {
            // Show loading indicator only on initial load (messages are null)
            DataState.INITIAL, DataState.LOADING -> {
                if (messages == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    // If refreshing, show the stale list. The Box handles the indicator.
                    MessageListSuccessContent(messages, onMessageClick)
                }
            }

            DataState.ERROR -> {
                FullScreenMessage(
                    icon = Icons.Filled.CloudOff,
                    title = "Error Loading Messages",
                    message = error ?: "An unknown error occurred."
                    // Retry is handled by the pull gesture via onRefresh
                )
            }

            DataState.SUCCESS -> {
                if (messages.isNullOrEmpty()) {
                    FullScreenMessage(
                        icon = Icons.Filled.Email,
                        title = "No Messages",
                        message = "This folder is empty."
                    )
                } else {
                    MessageListSuccessContent(messages, onMessageClick)
                }
            }
        }
    } // End PullToRefreshBox
}

// Extracted success state content (the message list) - No changes needed
@Composable
private fun MessageListSuccessContent(
    messages: List<Message>,
    onMessageClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { message ->
            MessageListItem(message = message, onClick = { onMessageClick(message.id) })
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

// Reusable composable for full-screen messages - No changes needed
@Composable
private fun FullScreenMessage(
    icon: ImageVector?,
    title: String,
    message: String? = null,
    content: (@Composable () -> Unit)? = null
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
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            if (message != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (content != null) {
                Spacer(modifier = Modifier.height(24.dp))
                content()
            }
        }
    }
}