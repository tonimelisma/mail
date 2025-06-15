package net.melisma.mail.ui.messagedetail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import net.melisma.core_data.model.Attachment
import net.melisma.mail.R
import timber.log.Timber
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun MessageDetailScreen(
    navController: NavHostController,
    viewModel: MessageDetailViewModel = hiltViewModel()
) {
    val screenState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(screenState.transientError) {
        screenState.transientError?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearTransientError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            val title = when (val overallMessageState = screenState.messageOverallState) {
                is MessageDetailUIState.Success -> {
                    overallMessageState.message.subject?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.title_message_detail_no_subject)
                }
                else -> stringResource(R.string.title_message_detail)
            }
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_up)
                        )
                    }
                },
                actions = {
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
        ) {
            when (val overallMessageState = screenState.messageOverallState) {
                MessageDetailUIState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is MessageDetailUIState.Error -> {
                    Text(
                        text = overallMessageState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                is MessageDetailUIState.Success -> {
                    val currentMessage = overallMessageState.message
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = currentMessage.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject_display),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.message_from_label, currentMessage.senderName ?: currentMessage.senderAddress ?: stringResource(R.string.unknown_sender)),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        val formatter =
                            remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
                        val parsedDate = remember(currentMessage.receivedDateTime) {
                            try {
                                OffsetDateTime.parse(currentMessage.receivedDateTime)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to parse date: ${currentMessage.receivedDateTime}")
                                null
                            }
                        }

                        val formattedDate =
                            parsedDate?.format(formatter) ?: currentMessage.receivedDateTime

                        Text(
                            text = stringResource(R.string.message_received_label, formattedDate),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        AnimatedContent(
                            targetState = screenState.bodyDisplayState,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                            },
                            label = "BodyDisplayStateAnimation"
                        ) { targetBodyState ->
                            when (targetBodyState) {
                                ContentDisplayState.DOWNLOADED -> {
                                    val htmlBody = currentMessage.body
                                    if (!htmlBody.isNullOrEmpty()) {
                                        AndroidView(
                                            factory = { factoryContext ->
                                                WebView(factoryContext).apply {
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
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(400.dp)
                                        )
                                    } else {
                                        Text(stringResource(R.string.message_body_empty), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                ContentDisplayState.LOADING -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.message_body_loading_details), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                ContentDisplayState.DOWNLOADING -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.message_body_downloading), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                ContentDisplayState.NOT_DOWNLOADED_WILL_DOWNLOAD_ON_WIFI -> {
                                    Text(stringResource(R.string.message_body_waiting_for_wifi_auto), style = MaterialTheme.typography.bodyLarge)
                                }
                                ContentDisplayState.NOT_DOWNLOADED_WILL_DOWNLOAD_WHEN_ONLINE -> {
                                    Text(stringResource(R.string.message_body_waiting_for_online_auto), style = MaterialTheme.typography.bodyLarge)
                                }
                                ContentDisplayState.NOT_DOWNLOADED_OFFLINE -> {
                                    Text(stringResource(R.string.message_body_offline_not_downloaded), style = MaterialTheme.typography.bodyLarge)
                                }
                                ContentDisplayState.NOT_DOWNLOADED_PREFERENCE_ON_DEMAND -> {
                                    Text(stringResource(R.string.message_body_not_downloaded_on_demand_pref), style = MaterialTheme.typography.bodyLarge)
                                }
                                ContentDisplayState.ERROR -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.cd_error_icon), tint = MaterialTheme.colorScheme.error)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.message_body_download_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                                        }
                                        TextButton(onClick = { viewModel.retryMessageBodyDownload() }) {
                                            Text(stringResource(R.string.action_retry))
                                        }
                                    }
                                }
                            }
                        }

                        if (currentMessage.attachments.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.attachments_title), style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                            ) {
                                items(items = currentMessage.attachments, key = { attachment -> attachment.id }) { attachmentItem: Attachment ->
                                    val attachmentState = screenState.attachmentDisplayStates[attachmentItem.id]
                                        ?: ContentDisplayState.LOADING
                                    AttachmentCard(
                                        attachment = attachmentItem,
                                        displayState = attachmentState,
                                        context = context,
                                        onRetryClick = { viewModel.retryAttachmentDownload(attachmentItem.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun AttachmentCard(
    attachment: Attachment,
    displayState: ContentDisplayState,
    context: android.content.Context,
    onRetryClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .height(120.dp)
            .clickable(enabled = displayState == ContentDisplayState.DOWNLOADED || displayState == ContentDisplayState.ERROR) {
                if (displayState == ContentDisplayState.DOWNLOADED && !attachment.localUri.isNullOrEmpty()) {
                    val uri = attachment.localUri!!.toUri()
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, attachment.contentType ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Timber.e(e, "No app found to open attachment: ${attachment.fileName}")
                    }
                } else if (displayState == ContentDisplayState.ERROR) {
                    onRetryClick()
                }
            },
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = displayState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                },
                label = "AttachmentStateAnimation"
            ) { targetDisplayState ->
                when (targetDisplayState) {
                    ContentDisplayState.LOADING,
                    ContentDisplayState.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    ContentDisplayState.DOWNLOADED -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = attachment.fileName, modifier = Modifier.size(32.dp))
                    ContentDisplayState.ERROR -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(id = R.string.cd_error_icon), modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.attachment_status_error_retry_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    ContentDisplayState.NOT_DOWNLOADED_WILL_DOWNLOAD_ON_WIFI -> Text(stringResource(R.string.attachment_status_waiting_wifi), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    ContentDisplayState.NOT_DOWNLOADED_WILL_DOWNLOAD_WHEN_ONLINE -> Text(stringResource(R.string.attachment_status_waiting_online), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    ContentDisplayState.NOT_DOWNLOADED_OFFLINE -> Text(stringResource(R.string.attachment_status_offline), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    ContentDisplayState.NOT_DOWNLOADED_PREFERENCE_ON_DEMAND -> Text(stringResource(R.string.attachment_status_on_demand_pref), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = attachment.fileName.ifBlank { stringResource(R.string.unknown_attachment_name) },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
} 