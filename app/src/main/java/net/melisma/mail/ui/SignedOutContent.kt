package net.melisma.mail.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.melisma.mail.R

// Composable for the Signed Out state
@Composable
fun SignedOutContent(
    isAuthInitialized: Boolean,
    authInitializationError: Exception?,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier // Accept modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { // Apply modifier to Box
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.welcome_message))
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onSignInClick,
                enabled = isAuthInitialized // Enable only when initialized
            ) {
                Text(stringResource(R.string.signin_with_microsoft_button))
            }

            // Show initialization status or error
            if (!isAuthInitialized && authInitializationError == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.status_initializing_auth),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (authInitializationError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.status_auth_failed,
                        authInitializationError.message
                            ?: stringResource(R.string.error_unknown_occurred)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}