package com.wafflehq.commander.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import com.wafflehq.commander.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface GateState {
    data object Loading : GateState
    data object NoConnection : GateState
    data object NeedsLogin : GateState
    data class Ready(val hasSelectedProject: Boolean) : GateState
}

@HiltViewModel
class ConnectionGateViewModel @Inject constructor(
    connectionSource: ConnectionSource,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _gateState = MutableStateFlow<GateState>(GateState.Loading)
    val gateState: StateFlow<GateState> = _gateState.asStateFlow()

    private var expiryWatcher: Job? = null

    init {
        viewModelScope.launch {
            connectionSource.session.combine(settingsRepository.selectedProjectName) { session, projectName ->
                session to projectName
            }.collect { (session, projectName) -> applyState(session, projectName) }
        }
    }

    private fun applyState(session: Session?, projectName: String?) {
        expiryWatcher?.cancel()
        val auth = session?.auth
        val isLoggedIn = auth != null && auth.expiresAt.isAfter(Instant.now())

        _gateState.value = when {
            session == null -> GateState.NoConnection
            !isLoggedIn -> GateState.NeedsLogin
            else -> GateState.Ready(hasSelectedProject = projectName != null)
        }

        if (isLoggedIn) {
            val expiresAt = requireNotNull(auth).expiresAt
            val delayMs = Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(0)
            expiryWatcher = viewModelScope.launch {
                delay(delayMs)
                _gateState.value = GateState.NeedsLogin
            }
        }
    }
}
