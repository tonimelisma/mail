package net.melisma.mail.ui.threaddetail

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.melisma.mail.R
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun FullMessageDisplayUnit(
    item: ThreadMessageItem,
    isExpandedInitially: Boolean = false, // To control if body is shown or needs a click
    onRequestLoadBody: (messageId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val message = item.message
    LocalContext.current

    // Trigger body load if it's NotLoadedYet and this unit is meant to be initially expanded
    // (e.g., for the first/latest message, or if all are expanded by default)
    // For now, let's assume we always try to load if NotLoadedYet, or user clicks a button.
    // A more sophisticated approach might only load for visible items or on explicit user interaction.
    LaunchedEffect(message.id, item.bodyState) {
        if (item.bodyState == BodyLoadingState.NotLoadedYet && isExpandedInitially) {
            onRequestLoadBody(message.id)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Message Headers (Similar to MessageListItem but perhaps styled differently)
            Text(
                text = message.senderName.takeIf { !it.isNullOrBlank() } ?: message.senderAddress
                ?: "Unknown Sender",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
            val parsedDate = remember(message.receivedDateTime) {
                try {
                    OffsetDateTime.parse(message.receivedDateTime).format(formatter)
                } catch (e: Exception) {
                    message.receivedDateTime
                }
            }
            Text(
                text = "To: ${message.recipientAddresses?.joinToString() ?: "-"} | Date: $parsedDate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!message.subject.isNullOrBlank()) {
                Text(
                    text = message.subject ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Message Body Section
            when (val bodyState = item.bodyState) {
                is BodyLoadingState.NotLoadedYet -> {
                    Button(onClick = { onRequestLoadBody(message.id) }) {
                        Text(stringResource(R.string.action_load_full_message))
                    }
                }

                is BodyLoadingState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is BodyLoadingState.Loaded -> {
                    val htmlBody = bodyState.htmlContent
                    if (htmlBody.isNotBlank()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.domStorageEnabled = true
                                    settings.defaultTextEncodingName = "utf-8"
                                    // Potentially adjust WebView height based on content if feasible
                                    // Or ensure it wraps content within the LazyColumn item boundaries.
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL(
                                    null,
                                    htmlBody,
                                    "text/html",
                                    "utf-8",
                                    null
                                )
                            },
                            modifier = Modifier.fillMaxWidth() // Adjust height as needed
                        )
                    } else {
                        Text(
                            "(No message body content)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                is BodyLoadingState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.error_loading_message_body,
                                bodyState.errorMessage
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { onRequestLoadBody(message.id) }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }
    }
} 