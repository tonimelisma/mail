package net.melisma.mail.ui.messagedetail

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import net.melisma.mail.R
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    navController: NavHostController,
    viewModel: MessageDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            val title = when (val state = uiState) {
                is MessageDetailUIState.Success -> state.mailMessage.subject
                    ?: stringResource(R.string.title_message_detail)

                else -> stringResource(R.string.title_message_detail)
            }
            TopAppBar(
                title = { Text(text = title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_up)
                        )
                    }
                },
                actions = {
                    // Placeholder for future actions
                    IconButton(onClick = { /* TODO: Implement more actions */ }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_actions)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            when (val state = uiState) {
                MessageDetailUIState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is MessageDetailUIState.Error -> {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is MessageDetailUIState.Success -> {
                    val message = state.mailMessage
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()), // Make content scrollable
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = message.subject ?: "(No Subject)",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "From: ${message.senderName ?: message.senderAddress ?: "Unknown Sender"}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        val formatter =
                            remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
                        val parsedDate = remember(message.receivedDateTime) {
                            try {
                                OffsetDateTime.parse(message.receivedDateTime)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        Text(
                            text = "Received: ${parsedDate?.format(formatter) ?: message.receivedDateTime}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        val htmlBody = message.body
                        if (!htmlBody.isNullOrEmpty()) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = false
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        settings.domStorageEnabled = true
                                        settings.defaultTextEncodingName = "utf-8"
                                        // Optional: For dark theme investigation later if needed
                                        // if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                                        //    WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                                        // }
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
                                modifier = Modifier.fillMaxWidth() // Ensure WebView takes width
                            )
                        } else {
                            Text("(No message body)", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
} 