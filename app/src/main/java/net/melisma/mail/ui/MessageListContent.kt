package net.melisma.mail.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.melisma.mail.Message

// Content Area: Shows Message List or Loading/Error
@SuppressLint("PullToRefreshState") // Suppress lint check if using experimental pull-to-refresh
@OptIn(ExperimentalMaterial3Api::class) // For PullToRefreshState or Badge
@Composable
fun MessageListContent(
    isLoading: Boolean,
    messages: List<Message>?,
    error: String?,
    onRefresh: () -> Unit,
    onMessageClick: (String) -> Unit
) {
    // --- Pull to Refresh Setup Placeholder ---
    // Dependency needed: implementation("androidx.compose.material:material-pull-refresh:1.0.0-beta01") // or later
    // val pullRefreshState = rememberPullToRefreshState() etc...
    // ---

    Box(modifier = Modifier.fillMaxSize()) { // Use simple Box for now

        when {
            isLoading && messages == null -> { // Initial load for this folder
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
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
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Error loading messages",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        error.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onRefresh) { Text("Retry") }
                    }
                }
            }

            messages == null -> { // Still waiting for initial selection/load for *any* folder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Select a folder", style = MaterialTheme.typography.bodyLarge)
                }
            }

            messages.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No messages", style = MaterialTheme.typography.bodyLarge)
                }
            }

            else -> {
                // Display the list of messages
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(messages, key = { it.id }) { message ->
                        MessageListItem(message = message, onClick = { onMessageClick(message.id) })
                        Divider(thickness = 0.5.dp)
                    }
                    // TODO: Pagination loading indicator / trigger
                }
            }
        }
        // Overlay a small indicator if actively refreshing an existing list
        if (isLoading && messages != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
    }
}