package net.melisma.mail.ui.threaddetail

import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
    onRequestLoadBody: (messageId: String) -> Unit,
    isExpandedInitially: Boolean,
    modifier: Modifier = Modifier
) {
    val message = item.message
    LocalContext.current

    LaunchedEffect(message.id, item.bodyState, isExpandedInitially) {
        if (item.bodyState == BodyLoadingState.Initial && isExpandedInitially) {
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
                ?: stringResource(R.string.unknown_sender),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
            val unknownDateText = stringResource(R.string.unknown_date)
            val parsedDate = remember(message.receivedDateTime, unknownDateText) {
                try {
                    message.receivedDateTime?.let { OffsetDateTime.parse(it).format(formatter) } ?: unknownDateText
                } catch (e: Exception) {
                    message.receivedDateTime ?: unknownDateText
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
                is BodyLoadingState.Initial -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text(stringResource(R.string.message_body_loading_details))
                    }
                }
                is BodyLoadingState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text(stringResource(R.string.message_body_downloading))
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
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL(
                                    null, htmlBody, "text/html", "utf-8", null
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(stringResource(R.string.message_body_empty), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is BodyLoadingState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                            .clickable { onRequestLoadBody(message.id) }
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = "Error loading body", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.error_loading_message_body, bodyState.errorMessage),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onRequestLoadBody(message.id) }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                BodyLoadingState.NotLoadedWillDownloadOnWifi -> {
                    Text(stringResource(R.string.message_body_waiting_for_wifi_auto), modifier = Modifier.padding(vertical = 8.dp))
                }
                BodyLoadingState.NotLoadedWillDownloadWhenOnline -> {
                    Text(stringResource(R.string.message_body_waiting_for_online_auto), modifier = Modifier.padding(vertical = 8.dp))
                }
                BodyLoadingState.NotLoadedOffline -> {
                    Text(stringResource(R.string.message_body_offline_not_downloaded), modifier = Modifier.padding(vertical = 8.dp))
                }
                BodyLoadingState.NotLoadedPreferenceAllowsLater -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                            .clickable { onRequestLoadBody(message.id) }
                    ) {
                        Text(stringResource(R.string.message_body_not_downloaded_on_demand_pref), modifier = Modifier.padding(vertical = 8.dp))
                        Text("(Tap to load now)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
} 