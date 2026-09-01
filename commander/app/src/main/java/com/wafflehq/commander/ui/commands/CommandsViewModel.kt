package com.wafflehq.commander.ui.commands

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.PathCommandEntry
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommandsUiState(
    val commands: List<PathCommandEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val startedCommandId: String? = null,
)

@HiltViewModel
class CommandsViewModel @Inject constructor(
    private val api: ClServerApi,
    private val usageRepository: UsageRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(CommandsUiState())
    val uiState: StateFlow<CommandsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val commands = api.getManifest().paths.firstOrNull { it.name == pathName }?.commands.orEmpty()
                _uiState.update { it.copy(commands = commands, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun runCommand(key: String) {
        viewModelScope.launch {
            try {
                val accepted = api.runPathCommand(pathName, key)
                _uiState.update { it.copy(startedCommandId = accepted.id) }
                usageRepository.refresh()
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun consumeStartedCommand() {
        _uiState.update { it.copy(startedCommandId = null) }
    }
}
