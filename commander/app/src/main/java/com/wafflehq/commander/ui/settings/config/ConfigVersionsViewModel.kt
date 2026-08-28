package com.wafflehq.commander.ui.settings.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ConfigVersionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigVersionsUiState(
    val versions: List<ConfigVersionSummary> = emptyList(),
    val activeVersionId: Int? = null,
    val loading: Boolean = true,
    val switching: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
)

@HiltViewModel
class ConfigVersionsViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigVersionsUiState())
    val uiState: StateFlow<ConfigVersionsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val versions = api.getConfigVersions().versions
                val activeVersionId = api.getConfigPointer().versionId
                _uiState.update { it.copy(versions = versions, activeVersionId = activeVersionId, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun activate(versionId: Int?) {
        _uiState.update { it.copy(switching = true, error = null, warning = null) }
        viewModelScope.launch {
            try {
                val response = api.setConfigPointer(versionId)
                _uiState.update { it.copy(switching = false, activeVersionId = response.versionId, warning = response.warning) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(switching = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
