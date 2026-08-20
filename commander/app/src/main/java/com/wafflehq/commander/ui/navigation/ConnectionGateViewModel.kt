package com.wafflehq.commander.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.connection.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ConnectionGateViewModel @Inject constructor(
    connectionRepository: ConnectionRepository,
) : ViewModel() {
    val hasConnection: StateFlow<Boolean?> = connectionRepository.connection
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
