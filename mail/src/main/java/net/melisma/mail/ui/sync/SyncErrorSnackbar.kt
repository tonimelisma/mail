package net.melisma.mail.ui.sync

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import net.melisma.core_data.model.SyncControllerStatus

@Composable
fun SyncErrorSnackbar(status: SyncControllerStatus) {
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(status.error) {
        status.error?.let { msg ->
            hostState.showSnackbar(message = msg, withDismissAction = true)
        }
    }

    SnackbarHost(hostState = hostState)
} 