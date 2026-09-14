package com.wafflehq.commander.ui.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SystemMetricsResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemMetricsUiState(
    val data: SystemMetricsResponse? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class SystemMetricsViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemMetricsUiState())
    val uiState: StateFlow<SystemMetricsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val data = api.getSystemMetrics()
                _uiState.update { it.copy(data = data, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
