package com.wafflehq.commander.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GateState {
    data object Loading : GateState
    data object NoConnection : GateState
    data object NeedsLogin : GateState
    data object Ready : GateState
}

@HiltViewModel
class ConnectionGateViewModel @Inject constructor(
    connectionSource: ConnectionSource,
) : ViewModel() {

    private val _gateState = MutableStateFlow<GateState>(GateState.Loading)
    val gateState: StateFlow<GateState> = _gateState.asStateFlow()

    private var expiryWatcher: Job? = null

    init {
        viewModelScope.launch {
            connectionSource.session.collect { session -> applyState(session) }
        }
    }

    private fun applyState(session: Session?) {
        expiryWatcher?.cancel()
        val auth = session?.auth
        val isLoggedIn = auth != null && auth.expiresAt.isAfter(Instant.now())

        _gateState.value = when {
            session == null -> GateState.NoConnection
            !isLoggedIn -> GateState.NeedsLogin
            else -> GateState.Ready
        }

        if (isLoggedIn && auth != null) {
            val delayMs = Duration.between(Instant.now(), auth.expiresAt).toMillis().coerceAtLeast(0)
            expiryWatcher = viewModelScope.launch {
                delay(delayMs)
                _gateState.value = GateState.NeedsLogin
            }
        }
    }
}
