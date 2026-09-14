package com.wafflehq.commander.ui.schedulers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SchedulerSummary
import com.wafflehq.commander.data.api.ScriptSchedulerSummary
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SchedulerKind { AGENT, SCRIPT }

data class SchedulersUiState(
    val schedulers: List<SchedulerSummary> = emptyList(),
    val scriptSchedulers: List<ScriptSchedulerSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val triggeringName: String? = null,
    val startedCommandId: String? = null,
)

@HiltViewModel
class SchedulersViewModel @Inject constructor(
    private val api: ClServerApi,
    private val usageRepository: UsageRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(SchedulersUiState())
    val uiState: StateFlow<SchedulersUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val result = api.getPathSchedulers(pathName)
                _uiState.update {
                    it.copy(
                        schedulers = result.schedulers,
                        scriptSchedulers = result.scriptSchedulers,
                        loading = false,
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun trigger(name: String, kind: SchedulerKind) {
        viewModelScope.launch {
            _uiState.update { it.copy(triggeringName = name) }
            try {
                val accepted = when (kind) {
                    SchedulerKind.AGENT -> api.triggerScheduler(pathName, name)
                    SchedulerKind.SCRIPT -> api.triggerScriptScheduler(pathName, name)
                }
                _uiState.update { it.copy(triggeringName = null, startedCommandId = accepted.id) }
                usageRepository.refresh()
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(triggeringName = null, error = error.message ?: "Unbekannter Fehler.")
                }
            }
        }
    }

    fun consumeStartedCommand() {
        _uiState.update { it.copy(startedCommandId = null) }
    }
}
