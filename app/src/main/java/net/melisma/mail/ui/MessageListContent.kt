package net.melisma.mail.ui

// No @SuppressLint import needed here anymore
// Import M3 Pull To Refresh correctly
// Other necessary imports
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
import androidx.compose.material.icons.filled.Folder
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

@OptIn(ExperimentalMaterial3Api::class) // Required for M3 pull-refresh
@Composable
fun MessageListContent(
    folderDataState: DataState,
    folderError: String?,
    messageDataState: DataState,
    messages: List<Message>?,
    messageError: String?,
    onRefresh: () -> Unit,
    onMessageClick: (String) -> Unit
) {
    // isRefreshing is true only if message state is LOADING *and* folder state is SUCCESS
    val isRefreshing = messageDataState == DataState.LOADING && folderDataState == DataState.SUCCESS
    // Note: PullToRefreshBox doesn't use rememberPullToRefreshState by default.
    // It manages state internally based on isRefreshing and the drag gesture.
    // We only need the state explicitly if customizing the indicator significantly.

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh, // ViewModel handles connectivity check now
        modifier = Modifier.fillMaxSize()
        // Default indicator is fine for now
    ) {

        // Check FOLDER state first
        when (folderDataState) {
            DataState.INITIAL, DataState.LOADING -> {
                FullScreenMessage(
                    icon = null,
                    title = "Loading Folders...",
                    content = { CircularProgressIndicator() })
            }

            DataState.ERROR -> {
                FullScreenMessage(
                    icon = Icons.Filled.Folder,
                    title = "Error Loading Folders",
                    message = folderError ?: "Could not load folder list."
                )
            }

            DataState.SUCCESS -> {
                // Folders loaded, now check MESSAGE state
                when (messageDataState) {
                    DataState.INITIAL -> {
                        FullScreenMessage(icon = null, title = "Select a folder")
                    }

                    DataState.LOADING -> {
                        // Show stale messages if available, otherwise blank (indicator is handled by PullToRefreshBox)
                        if (messages != null) {
                            MessageListSuccessContent(messages, onMessageClick)
                        } else {
                            Spacer(Modifier.fillMaxSize()) // Blank content area, indicator shows at top
                        }
                    }

                    DataState.ERROR -> {
                        FullScreenMessage(
                            icon = Icons.Filled.CloudOff,
                            title = "Error Loading Messages",
                            message = messageError ?: "An unknown error occurred."
                            // Retry is via pull gesture
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
                } // End inner when (messageDataState)
            }
        } // End outer when (folderDataState)
    } // End PullToRefreshBox
}

// Extracted success state content (the message list)
@Composable
private fun MessageListSuccessContent(
    messages: List<Message>,
    onMessageClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { message ->
            MessageListItem(
                message = message,
                onClick = { onMessageClick(message.id) }) // Assumes MessageListItem is defined/imported
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

// Reusable composable for full-screen messages
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
                    imageVector = icon, contentDescription = title,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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

// --- Imports assumed present for MessageListItem ---
// import net.melisma.mail.ui.MessageListItem
// import androidx.compose.material3.HorizontalDivider