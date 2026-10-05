package com.wafflehq.commander.ui.allsessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ActiveSession
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SessionActivity
import com.wafflehq.commander.data.api.sessionActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SESSIONS_POLL_INTERVAL_MS = 3_000L

data class AllSessionsUiState(
    val sessions: List<ActiveSession> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val killingSessionId: String? = null,
) {
    val workingCount: Int get() = sessions.count { it.sessionActivity() == SessionActivity.Working }
    val waitingCount: Int get() = sessions.count { it.sessionActivity() == SessionActivity.Waiting }
    val idleCount: Int get() = sessions.count { it.sessionActivity() == SessionActivity.Idle }
}

fun sortSessions(sessions: List<ActiveSession>): List<ActiveSession> =
    sessions.sortedWith(
        compareBy<ActiveSession> { it.sessionActivity().ordinal }.thenByDescending { it.startedAt },
    )

@HiltViewModel
class AllSessionsViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllSessionsUiState())
    val uiState: StateFlow<AllSessionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            loadSessions(reportError = true)
        }
    }

    suspend fun pollSessions() {
        while (currentCoroutineContext().isActive) {
            delay(SESSIONS_POLL_INTERVAL_MS)
            loadSessions(reportError = false)
        }
    }

    fun kill(session: ActiveSession) {
        if (_uiState.value.killingSessionId != null) return
        _uiState.update { it.copy(killingSessionId = session.sessionId, error = null) }
        viewModelScope.launch {
            try {
                api.killSession(session.sessionId)
                _uiState.update { it.copy(killingSessionId = null) }
                loadSessions(reportError = true)
            } catch (error: ApiException) {
                _uiState.update { it.copy(killingSessionId = null, error = error.message) }
            }
        }
    }

    private suspend fun loadSessions(reportError: Boolean) {
        try {
            val sessions = sortSessions(api.getAllSessions())
            _uiState.update { it.copy(sessions = sessions, loading = false) }
        } catch (error: ApiException) {
            _uiState.update {
                it.copy(loading = false, error = if (reportError) error.message else it.error)
            }
        }
    }
}
