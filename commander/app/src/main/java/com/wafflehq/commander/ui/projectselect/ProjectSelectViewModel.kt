package com.wafflehq.commander.ui.projectselect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectSelectUiState(
    val paths: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val selected: String? = null,
)

@HiltViewModel
class ProjectSelectViewModel @Inject constructor(
    private val api: ClServerApi,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectSelectUiState())
    val uiState: StateFlow<ProjectSelectUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val paths = api.getManifest().paths.map { it.name }
                _uiState.update { it.copy(paths = paths, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun selectProject(name: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedProject(name)
            _uiState.update { it.copy(selected = name) }
        }
    }
}
