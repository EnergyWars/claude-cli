package com.wafflehq.commander.ui.schedulers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SchedulerOverview
import com.wafflehq.commander.data.api.ScriptSchedulerOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AllSchedulersUiState(
    val schedulers: List<SchedulerOverview> = emptyList(),
    val scriptSchedulers: List<ScriptSchedulerOverview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AllSchedulersViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllSchedulersUiState())
    val uiState: StateFlow<AllSchedulersUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val schedulers = api.getAllSchedulers().schedulers
                val scriptSchedulers = api.getAllScriptSchedulers().scriptSchedulers
                _uiState.update {
                    it.copy(schedulers = schedulers, scriptSchedulers = scriptSchedulers, loading = false)
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
