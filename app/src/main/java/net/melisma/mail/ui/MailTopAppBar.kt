package net.melisma.mail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.microsoft.identity.client.IAccount

// Top App Bar Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailTopAppBar(
    title: String,
    account: IAccount?,
    onNavigationClick: () -> Unit
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(Icons.Filled.Menu, contentDescription = "Open Navigation Drawer")
            }
        },
        actions = {
            if (account != null) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = "Signed in as ${account.username}"
                )
            }
            // Placeholder for Search Icon etc.
        }
    )
}