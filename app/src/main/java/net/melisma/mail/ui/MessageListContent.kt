package net.melisma.mail.ui

// Import M3 Pull To Refresh correctly
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
import androidx.compose.ui.res.stringResource // Import for string resources
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.melisma.mail.DataState
import net.melisma.mail.Message
import net.melisma.mail.R // Import R class for resources

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

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {

        // Check FOLDER state first
        when (folderDataState) {
            DataState.INITIAL, DataState.LOADING -> {
                FullScreenMessage(
                    icon = null, // No icon needed for generic loading state
                    iconContentDescription = null, // Pass null as no icon is shown
                    title = stringResource(R.string.loading_folders), // Use string resource
                    content = { CircularProgressIndicator() }
                )
            }

            DataState.ERROR -> {
                FullScreenMessage(
                    icon = Icons.Filled.Folder,
                    iconContentDescription = stringResource(R.string.cd_error_loading_folders), // Content description for icon
                    title = stringResource(R.string.error_loading_folders_title), // Use string resource
                    message = folderError
                        ?: stringResource(R.string.error_could_not_load_folder_list) // Use string resource
                )
            }

            DataState.SUCCESS -> {
                // Folders loaded, now check MESSAGE state
                when (messageDataState) {
                    DataState.INITIAL -> {
                        FullScreenMessage(
                            icon = Icons.Filled.Folder,
                            iconContentDescription = stringResource(R.string.cd_select_folder_prompt),
                            title = stringResource(R.string.select_a_folder)
                        )
                    }

                    DataState.LOADING -> {
                        // Show stale messages if available, otherwise blank (indicator is handled by PullToRefreshBox)
                        if (messages != null) {
                            MessageListSuccessContent(messages, onMessageClick)
                        } else {
                            // Content area is intentionally blank while loading indicator shows at top via PullToRefreshBox state
                            Spacer(Modifier.fillMaxSize())
                        }
                    }

                    DataState.ERROR -> {
                        FullScreenMessage(
                            icon = Icons.Filled.CloudOff,
                            iconContentDescription = stringResource(R.string.cd_error_loading_messages), // Content description for icon
                            title = stringResource(R.string.error_loading_messages_title), // Use string resource
                            message = messageError
                                ?: stringResource(R.string.error_unknown_occurred) // Use string resource
                            // Retry is via pull gesture
                        )
                    }

                    DataState.SUCCESS -> {
                        if (messages.isNullOrEmpty()) {
                            FullScreenMessage(
                                icon = Icons.Filled.Email,
                                iconContentDescription = stringResource(R.string.cd_no_messages), // Content description for icon
                                title = stringResource(R.string.no_messages_title), // Use string resource
                                message = stringResource(R.string.folder_is_empty) // Use string resource
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
                onClick = { onMessageClick(message.id) })
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

// Reusable composable for full-screen messages
@Composable
private fun FullScreenMessage(
    icon: ImageVector?,
    iconContentDescription: String?, // Added parameter for icon description
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
                    contentDescription = iconContentDescription, // Use passed content description
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