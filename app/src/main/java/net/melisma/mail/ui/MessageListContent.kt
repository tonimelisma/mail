// File: app/src/main/java/net/melisma/mail/ui/MessageListContent.kt
// Cleaned: Removed duplicate composables, fixed PullToRefreshBox params, added imports

package net.melisma.mail.ui

// Import generic Account
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
import net.melisma.mail.DataState
import net.melisma.mail.Message
import net.melisma.mail.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListContent(
    messageDataState: DataState,
    messages: List<Message>?,
    messageError: String?,
    accountContext: Account?, // Uses generic Account
    onRefresh: () -> Unit,
    onMessageClick: (String) -> Unit
) {
    val isRefreshing = messageDataState == DataState.LOADING

    Column(modifier = Modifier.fillMaxSize()) {
        if (accountContext != null) {
            AccountContextHeader(account = accountContext) // Pass generic Account
            HorizontalDivider()
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing, // Pass parameter
            onRefresh = onRefresh,       // Pass parameter
            modifier = Modifier
                .fillMaxSize()
                .weight(1f) // Use weight modifier from ColumnScope
        ) {
            when (messageDataState) {
                DataState.INITIAL -> {
                    if (messages != null) MessageListSuccessContent(messages, onMessageClick)
                    else FullScreenMessage(null, null, stringResource(R.string.select_a_folder))
                }
                DataState.LOADING -> {
                    if (messages != null) MessageListSuccessContent(messages, onMessageClick)
                    else Spacer(Modifier.fillMaxSize())
                }
                DataState.ERROR -> {
                    FullScreenMessage(
                        Icons.Filled.CloudOff,
                        stringResource(R.string.cd_error_loading_messages),
                        stringResource(R.string.error_loading_messages_title),
                        messageError ?: stringResource(R.string.error_unknown_occurred)
                    )
                }
                DataState.SUCCESS -> {
                    if (messages.isNullOrEmpty()) FullScreenMessage(
                        Icons.Filled.Email,
                        stringResource(R.string.cd_no_messages),
                        stringResource(R.string.no_messages_title),
                        stringResource(R.string.folder_is_empty)
                    )
                    else MessageListSuccessContent(messages, onMessageClick)
                }
            }
        } // End PullToRefreshBox
    } // End Column
}

// --- Helper Composables (Single Definitions) ---

@Composable
private fun MessageListSuccessContent(messages: List<Message>, onMessageClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { message ->
            // Assumes MessageListItem exists and is correct
            MessageListItem(message = message, onClick = { onMessageClick(message.id) })
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
private fun FullScreenMessage(
    icon: ImageVector?, iconContentDescription: String?, title: String,
    message: String? = null, content: (@Composable () -> Unit)? = null
) {
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = iconContentDescription,
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

@Composable
private fun AccountContextHeader(account: Account) { // Uses generic Account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = account.username, // Use username from generic Account
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}