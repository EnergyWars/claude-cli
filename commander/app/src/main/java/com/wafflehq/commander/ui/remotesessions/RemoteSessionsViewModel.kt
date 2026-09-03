package com.wafflehq.commander.ui.remotesessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.RemoteAgentSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteSessionsUiState(
    val sessions: List<RemoteAgentSession> = emptyList(),
    val loading: Boolean = true,
    val starting: Boolean = false,
    val error: String? = null,
    val nameInput: String = "",
    val lastStartedId: String? = null,
)

@HiltViewModel
class RemoteSessionsViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(RemoteSessionsUiState())
    val uiState: StateFlow<RemoteSessionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _uiState.update { it.copy(loading = true, error = null) }
            try {
                val sessions = api.getRemoteSessions(pathName)
                _uiState.update { it.copy(sessions = sessions, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onNameInputChange(value: String) {
        _uiState.update { it.copy(nameInput = value) }
    }

    fun startSession() {
        if (_uiState.value.starting) return
        val name = _uiState.value.nameInput.trim().ifEmpty { null }

        _uiState.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val result = api.startRemoteSession(pathName, name)
                _uiState.update { it.copy(starting = false, nameInput = "", lastStartedId = result.id) }
                refresh(showLoading = false)
            } catch (error: ApiException) {
                _uiState.update { it.copy(starting = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
