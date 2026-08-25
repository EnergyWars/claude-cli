package com.wafflehq.commander.ui.projecthome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectHomeUiState(
    val availablePaths: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ProjectHomeViewModel @Inject constructor(
    private val api: ClServerApi,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val selectedProjectName: StateFlow<String?> = settingsRepository.selectedProjectName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _uiState = MutableStateFlow(ProjectHomeUiState())
    val uiState: StateFlow<ProjectHomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val paths = api.getManifest().paths.map { it.name }
                _uiState.update { it.copy(availablePaths = paths, error = null) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onProjectSelected(name: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedProject(name)
        }
    }
}
