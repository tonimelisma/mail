package net.melisma.mail.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.paging.compose.LazyPagingItems
import net.melisma.core_data.model.Message
import net.melisma.mail.MainViewModel
import net.melisma.mail.MainScreenState

@Composable
fun MessageListScreen(
    lazyMessageItems: LazyPagingItems<Message>,
    onMessageClick: (Message) -> Unit,
    state: MainScreenState,
    mainViewModel: MainViewModel,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lazyMessageItems.itemSnapshotList.items) { _, msg ->
            msg?.let {
                ListItem(
                    headlineContent = { Text(it.subject ?: "(No subject)") },
                    supportingContent = { Text(it.bodyPreview ?: "") },
                    modifier = Modifier.clickable { onMessageClick(it) }
                )
            }
        }
    }
}

@Composable
fun UnifiedInboxScreen(
    lazyMessageItems: LazyPagingItems<Message>,
    onMessageClick: (Message) -> Unit,
    state: MainScreenState,
    mainViewModel: MainViewModel,
) {
    // For now, reuse the MessageListScreen implementation
    MessageListScreen(lazyMessageItems, onMessageClick, state, mainViewModel)
} 