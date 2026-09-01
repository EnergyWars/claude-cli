package com.wafflehq.commander.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ManifestAgent
import com.wafflehq.commander.data.api.agentNameOrNull
import com.wafflehq.commander.data.context.DevContextRepository
import com.wafflehq.commander.data.db.DevContextEntity
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

val RUN_AGENT_MODELS = listOf("", "haiku", "sonnet", "opus", "fable")

/** Keine Kontexte gewaehlt -&gt; nur der Prompt; sonst alle Kontext-Werte der Reihe nach vor den Prompt gehaengt, Prompt optional. */
fun buildAgentCommand(contextValues: List<String>, prompt: String): String = when {
    contextValues.isEmpty() -> prompt
    prompt.isBlank() -> contextValues.joinToString("\n\n")
    else -> (contextValues + prompt).joinToString("\n\n")
}

data class RunAgentUiState(
    val agentCommand: String = "",
    val agentDescription: String = "",
    val pathName: String = "",
    val contexts: List<DevContextEntity> = emptyList(),
    val selectedContextIds: Set<Long> = emptySet(),
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
    private val usageRepository: UsageRepository,
    devContextRepository: DevContextRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agentCommand: String = checkNotNull(savedStateHandle["agentCommand"])
    private val pathName: String = checkNotNull(savedStateHandle["pathName"])
    private val prefillPrompt: String? = savedStateHandle["prefillPrompt"]

    private val _uiState = MutableStateFlow(
        RunAgentUiState(agentCommand = agentCommand, pathName = pathName, prompt = prefillPrompt.orEmpty()),
    )
    val uiState: StateFlow<RunAgentUiState> = _uiState.asStateFlow()

    private var resolvedAgent: ManifestAgent? = null

    init {
        viewModelScope.launch {
            try {
                val manifest = api.getManifest()
                val agent = manifest.agents.firstOrNull { it.command == agentCommand }
                resolvedAgent = agent
                _uiState.update { it.copy(agentDescription = agent?.description.orEmpty(), loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
        viewModelScope.launch {
            devContextRepository.contexts.collect { list -> _uiState.update { it.copy(contexts = list) } }
        }
    }

    fun onContextToggled(id: Long) {
        _uiState.update {
            val selected = it.selectedContextIds
            it.copy(selectedContextIds = if (id in selected) selected - id else selected + id)
        }
    }

    fun onModelSelected(index: Int) {
        _uiState.update { it.copy(selectedModelIndex = index) }
    }

    fun onPromptChange(value: String) {
        _uiState.update { it.copy(prompt = value) }
    }

    fun start() {
        val state = _uiState.value
        val contextValues = state.contexts.filter { it.id in state.selectedContextIds }.map { it.value }
        if (contextValues.isEmpty() && state.prompt.isBlank()) {
            _uiState.update { it.copy(error = "Prompt angeben.") }
            return
        }
        val model = RUN_AGENT_MODELS.getOrNull(state.selectedModelIndex)?.ifEmpty { null }
        val command = buildAgentCommand(contextValues, state.prompt)
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val agentName = resolvedAgent?.agentNameOrNull()
                val accepted = api.runAgent(agentName, state.pathName, command, model)
                _uiState.update { it.copy(submitting = false, createdCommandId = accepted.id) }
                usageRepository.refresh()
            } catch (error: ApiException) {
                _uiState.update { it.copy(submitting = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
