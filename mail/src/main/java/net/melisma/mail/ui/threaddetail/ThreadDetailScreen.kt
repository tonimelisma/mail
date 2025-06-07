package net.melisma.mail.ui.threaddetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import net.melisma.mail.R
import net.melisma.mail.ui.FullScreenMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailScreen(
    navController: NavHostController,
    viewModel: ThreadDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            val title = when (val state = uiState.threadLoadingState) {
                is ThreadDetailUIState.Success -> state.threadSubject
                    ?: stringResource(R.string.title_thread_detail_fallback)

                else -> stringResource(R.string.title_thread_detail_fallback)
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
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState.threadLoadingState) {
                is ThreadDetailUIState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is ThreadDetailUIState.Error -> {
                    FullScreenMessage(
                        icon = null, // Or an error icon
                        iconContentDescription = null,
                        title = stringResource(R.string.error_loading_thread_details_title),
                        message = state.errorMessage
                    )
                }

                is ThreadDetailUIState.Success -> {
                    if (state.threadMessages.isEmpty()) {
                        FullScreenMessage(
                            icon = null, // Or an empty icon
                            iconContentDescription = null,
                            title = stringResource(R.string.thread_empty_title),
                            message = stringResource(R.string.thread_empty_message)
                        )
                    } else {
                        LazyColumn(modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)) {
                            items(
                                state.threadMessages,
                                key = { it.message.id }) { threadMessageItem ->
                                FullMessageDisplayUnit(
                                    item = threadMessageItem,
                                    isExpandedInitially = true,
                                    onRequestLoadBody = {
                                        viewModel.requestMessageBody(threadMessageItem.message.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
} 