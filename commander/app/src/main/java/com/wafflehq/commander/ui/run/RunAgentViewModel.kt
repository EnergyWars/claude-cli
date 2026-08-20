package com.wafflehq.commander.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ManifestAgent
import com.wafflehq.commander.data.api.agentNameOrNull
import com.wafflehq.commander.data.history.CommandHistoryRepository
import com.wafflehq.commander.data.history.CommandKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

val RUN_AGENT_MODELS = listOf("", "haiku", "sonnet", "opus", "fable")

data class RunAgentUiState(
    val agents: List<ManifestAgent> = emptyList(),
    val paths: List<String> = emptyList(),
    val selectedAgentIndex: Int = 0,
    val selectedPathIndex: Int = -1,
    val selectedModelIndex: Int = 0,
    val prompt: String = "",
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
    val createdCommandId: String? = null,
)

@HiltViewModel
class RunAgentViewModel @Inject constructor(
    private val api: ClServerApi,
    private val historyRepository: CommandHistoryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialAgentCommand: String? = savedStateHandle["agent"]
    private val initialPath: String? = savedStateHandle["path"]

    private val _uiState = MutableStateFlow(RunAgentUiState())
    val uiState: StateFlow<RunAgentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val manifest = api.getManifest()
                val pathNames = manifest.paths.map { it.name }
                _uiState.update {
                    it.copy(
                        agents = manifest.agents,
                        paths = pathNames,
                        selectedAgentIndex = manifest.agents.indexOfFirst { agent -> agent.command == initialAgentCommand }
                            .let { index -> if (index < 0) 0 else index },
                        selectedPathIndex = initialPath?.let(pathNames::indexOf) ?: -1,
                        loading = false,
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onAgentSelected(index: Int) {
        _uiState.update { it.copy(selectedAgentIndex = index) }
    }

    fun onPathSelected(index: Int) {
        _uiState.update { it.copy(selectedPathIndex = index) }
    }

    fun onModelSelected(index: Int) {
        _uiState.update { it.copy(selectedModelIndex = index) }
    }

    fun onPromptChange(value: String) {
        _uiState.update { it.copy(prompt = value) }
    }

    fun start() {
        val state = _uiState.value
        val path = state.paths.getOrNull(state.selectedPathIndex)
        val agent = state.agents.getOrNull(state.selectedAgentIndex)
        if (path == null || agent == null || state.prompt.isBlank()) {
            _uiState.update { it.copy(error = "Pfad und Prompt angeben.") }
            return
        }
        val model = RUN_AGENT_MODELS.getOrNull(state.selectedModelIndex)?.ifEmpty { null }
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val accepted = api.runAgent(agent.agentNameOrNull(), path, state.prompt, model)
                historyRepository.record(accepted.id, CommandKind.AGENT, agent.command, path)
                _uiState.update { it.copy(submitting = false, createdCommandId = accepted.id) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(submitting = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
