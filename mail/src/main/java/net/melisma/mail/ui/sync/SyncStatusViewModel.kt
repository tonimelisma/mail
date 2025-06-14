package net.melisma.mail.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import net.melisma.data.sync.SyncController
import net.melisma.core_data.model.SyncControllerStatus
import javax.inject.Inject

@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val syncController: SyncController,
) : ViewModel() {

    val status: StateFlow<SyncControllerStatus> = syncController.status
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = SyncControllerStatus()
        )
} 