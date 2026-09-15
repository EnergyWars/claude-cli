package com.wafflehq.commander.ui.projecthome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.UsageLimit
import com.wafflehq.commander.data.settings.SettingsRepository
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val USAGE_POLL_INTERVAL_MS = 60_000L

data class ProjectHomeUiState(
    val availablePaths: List<String> = emptyList(),
    val usageLimits: List<UsageLimit> = emptyList(),
    val usageLastUpdatedAt: Instant? = null,
    val usageRefreshing: Boolean = false,
    val hasSchedulers: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ProjectHomeViewModel @Inject constructor(
    private val api: ClServerApi,
    private val settingsRepository: SettingsRepository,
    private val usageRepository: UsageRepository,
) : ViewModel() {

    val selectedProjectName: StateFlow<String?> = settingsRepository.selectedProjectName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val usageBannerExpanded: StateFlow<Boolean> = settingsRepository.usageBannerExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _uiState = MutableStateFlow(ProjectHomeUiState())
    val uiState: StateFlow<ProjectHomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            usageRepository.usageLimits.collect { limits ->
                _uiState.update { it.copy(usageLimits = limits) }
            }
        }
        viewModelScope.launch {
            usageRepository.lastUpdatedAt.collect { lastUpdatedAt ->
                _uiState.update { it.copy(usageLastUpdatedAt = lastUpdatedAt) }
            }
        }
        viewModelScope.launch {
            usageRepository.isRefreshing.collect { refreshing ->
                _uiState.update { it.copy(usageRefreshing = refreshing) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                usageRepository.refresh()
                delay(USAGE_POLL_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            selectedProjectName.collect { name -> refreshSchedulerAvailability(name) }
        }
    }

    private fun refreshSchedulerAvailability(pathName: String?) {
        if (pathName == null) {
            _uiState.update { it.copy(hasSchedulers = false) }
            return
        }
        viewModelScope.launch {
            try {
                val result = api.getPathSchedulers(pathName)
                _uiState.update {
                    it.copy(hasSchedulers = result.schedulers.isNotEmpty() || result.scriptSchedulers.isNotEmpty())
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(hasSchedulers = false) }
            }
        }
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

    fun onUsageBannerExpandedChanged(expanded: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUsageBannerExpanded(expanded)
        }
    }

    fun refreshUsage() {
        viewModelScope.launch {
            usageRepository.refresh()
        }
    }
}
