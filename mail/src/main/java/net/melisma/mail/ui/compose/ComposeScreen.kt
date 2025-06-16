package net.melisma.mail.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    navController: NavHostController,
    viewModel: ComposeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.addAttachment(uri, context)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendEvent.collect {
            navController.navigateUp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { attachLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Attach")
                    }
                    Button(
                        onClick = viewModel::send,
                        enabled = !uiState.isSending
                    ) {
                        Text("Send")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            TextField(
                value = uiState.draft.to.joinToString { it.emailAddress },
                onValueChange = viewModel::onToChanged,
                label = { Text("To") },
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                value = uiState.draft.subject,
                onValueChange = viewModel::onSubjectChanged,
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                value = uiState.draft.body,
                onValueChange = viewModel::onBodyChanged,
                label = { Text("Body") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            if (uiState.draft.attachments.isNotEmpty()) {
                LazyRow(modifier = Modifier.fillMaxWidth()) {
                    items(uiState.draft.attachments) { att ->
                        AssistChip(
                            onClick = { /* maybe preview */ },
                            label = { Text(att.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.removeAttachment(att) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove attachment")
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }
        }
    }
} 