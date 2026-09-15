package com.wafflehq.commander.ui.schedulers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SchedulerPathStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SchedulerDetailUiState(
    val description: String = "",
    val cron: String = "",
    val executionText: String = "",
    val pathStatuses: List<SchedulerPathStatus> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val updatingPathName: String? = null,
)

@HiltViewModel
class SchedulerDetailViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val schedulerName: String = checkNotNull(savedStateHandle["name"])
    val kind: SchedulerKind = SchedulerKind.valueOf(checkNotNull(savedStateHandle["kind"]))

    private val _uiState = MutableStateFlow(SchedulerDetailUiState())
    val uiState: StateFlow<SchedulerDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                when (kind) {
                    SchedulerKind.AGENT -> {
                        val scheduler = api.getAllSchedulers().schedulers.find { it.name == schedulerName }
                        if (scheduler == null) {
                            _uiState.update { it.copy(loading = false, error = "Scheduler nicht gefunden.") }
                        } else {
                            _uiState.update {
                                it.copy(
                                    description = scheduler.description,
                                    cron = scheduler.cron,
                                    executionText = scheduler.instructions,
                                    pathStatuses = scheduler.pathStatuses,
                                    loading = false,
                                )
                            }
                        }
                    }
                    SchedulerKind.SCRIPT -> {
                        val scheduler = api.getAllScriptSchedulers().scriptSchedulers.find { it.name == schedulerName }
                        if (scheduler == null) {
                            _uiState.update { it.copy(loading = false, error = "Scheduler nicht gefunden.") }
                        } else {
                            _uiState.update {
                                it.copy(
                                    description = scheduler.description,
                                    cron = scheduler.cron,
                                    executionText = scheduler.script,
                                    pathStatuses = scheduler.pathStatuses,
                                    loading = false,
                                )
                            }
                        }
                    }
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun setEnabled(pathName: String, enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(updatingPathName = pathName) }
            try {
                when (kind) {
                    SchedulerKind.AGENT -> api.setSchedulerEnabled(pathName, schedulerName, enabled)
                    SchedulerKind.SCRIPT -> api.setScriptSchedulerEnabled(pathName, schedulerName, enabled)
                }
                _uiState.update { current ->
                    current.copy(
                        updatingPathName = null,
                        pathStatuses = current.pathStatuses.map {
                            if (it.pathName == pathName) it.copy(enabled = enabled) else it
                        },
                    )
                }
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(updatingPathName = null, error = error.message ?: "Unbekannter Fehler.")
                }
            }
        }
    }
}
