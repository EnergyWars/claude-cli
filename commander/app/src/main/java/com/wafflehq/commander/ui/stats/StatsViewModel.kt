package com.wafflehq.commander.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ProjectStats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val stats: ProjectStats? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val stats = api.getStats(pathName)
                _uiState.update { it.copy(stats = stats, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
