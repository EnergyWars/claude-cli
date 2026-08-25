package com.wafflehq.commander.ui.collect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CollectSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectUiState(
    val targetName: String = "",
    val loading: Boolean = false,
    val summary: CollectSummary? = null,
    val error: String? = null,
)

@HiltViewModel
class CollectViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectUiState())
    val uiState: StateFlow<CollectUiState> = _uiState.asStateFlow()

    fun onTargetNameChange(value: String) {
        _uiState.update { it.copy(targetName = value) }
    }

    fun collectAll() = collect(targetName = null)

    fun collectOne() = collect(targetName = _uiState.value.targetName)

    private fun collect(targetName: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, summary = null) }
            try {
                val summary = api.collect(targetName)
                _uiState.update { it.copy(loading = false, summary = summary) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
