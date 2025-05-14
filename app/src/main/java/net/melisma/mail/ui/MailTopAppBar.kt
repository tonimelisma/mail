package net.melisma.mail.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import net.melisma.mail.R // Ensure R is imported

// Top App Bar Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailTopAppBar(
    title: String,
    onNavigationClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {} // Added actions parameter
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.cd_open_navigation_drawer)
                )
            }
        },
        actions = actions // Use the actions parameter here
    )
}