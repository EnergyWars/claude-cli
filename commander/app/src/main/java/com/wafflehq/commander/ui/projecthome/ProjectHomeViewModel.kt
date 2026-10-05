package com.wafflehq.commander.ui.projecthome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.DEFAULT_REMOTE_SESSION_MODEL
import com.wafflehq.commander.data.api.UsageLimit
import com.wafflehq.commander.data.api.isReady
import com.wafflehq.commander.data.api.startableCount
import com.wafflehq.commander.data.settings.SettingsRepository
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val USAGE_POLL_INTERVAL_MS = 60_000L
private const val GOALS_POLL_INTERVAL_MS = 10_000L
private const val MAX_START_COUNT_DIGITS = 4

enum class GoalCommand(val slashCommand: String) {
    Plan("/goal-plan"),
    Prompt("/goal-prompt"),
}

fun GoalCommand.buildCommand(text: String): String = "${slashCommand} ${text.trim()}"

data class ProjectHomeUiState(
    val availablePaths: List<String> = emptyList(),
    val usageLimits: List<UsageLimit> = emptyList(),
    val usageLastUpdatedAt: Instant? = null,
    val usageRefreshing: Boolean = false,
    val hasSchedulers: Boolean = false,
    val startableGoalCount: Int = 0,
    val startingAllGoals: Boolean = false,
    val startAllModel: String = DEFAULT_REMOTE_SESSION_MODEL,
    val startAllMaxCount: Int? = null,
    val startingRemoteSession: Boolean = false,
    val remoteSessionStartedId: String? = null,
    val startingGoalCommand: Boolean = false,
    val startedCommandId: String? = null,
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
        viewModelScope.launch {
            selectedProjectName.collectLatest { name -> pollStartableGoals(name) }
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

    private suspend fun pollStartableGoals(pathName: String?) {
        if (pathName == null) {
            _uiState.update { it.copy(startableGoalCount = 0) }
            return
        }
        while (currentCoroutineContext().isActive) {
            reloadStartableGoalCount(pathName)
            delay(GOALS_POLL_INTERVAL_MS)
        }
    }

    private suspend fun reloadStartableGoalCount(pathName: String) {
        val count = try {
            api.getGoals(pathName).sumOf { it.startableCount() }
        } catch (_: ApiException) {
            0
        }
        _uiState.update { it.copy(startableGoalCount = count) }
    }

    fun onStartAllModelSelected(model: String) {
        _uiState.update { it.copy(startAllModel = model) }
    }

    fun onStartAllMaxCountChanged(input: String) {
        val count = input.filter { it.isDigit() }.take(MAX_START_COUNT_DIGITS).toIntOrNull()?.takeIf { it > 0 }
        _uiState.update { it.copy(startAllMaxCount = count) }
    }

    fun startAllGoals() {
        val pathName = selectedProjectName.value ?: return
        if (_uiState.value.startingAllGoals) return
        val model = _uiState.value.startAllModel
        val maxCount = _uiState.value.startAllMaxCount
        _uiState.update { it.copy(startingAllGoals = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            var startedAny = false
            try {
                val targets = api.getGoals(pathName).flatMap { group ->
                    group.goals.filter { it.isReady() && !it.running }.map { group.folder to it.fileName }
                }.let { if (maxCount == null) it else it.take(maxCount) }
                for ((folder, fileName) in targets) {
                    try {
                        api.startGoal(pathName, folder, fileName, model)
                        startedAny = true
                    } catch (error: ApiException) {
                        failure = error.message ?: "Unbekannter Fehler."
                    }
                }
            } catch (error: ApiException) {
                failure = error.message ?: "Unbekannter Fehler."
            }
            if (startedAny) usageRepository.refresh()
            reloadStartableGoalCount(pathName)
            _uiState.update { it.copy(startingAllGoals = false, error = failure) }
        }
    }

    fun startRemoteSession() {
        val pathName = selectedProjectName.value ?: return
        if (_uiState.value.startingRemoteSession) return
        _uiState.update { it.copy(startingRemoteSession = true, remoteSessionStartedId = null, error = null) }
        viewModelScope.launch {
            try {
                val result = api.startRemoteSession(pathName, model = DEFAULT_REMOTE_SESSION_MODEL)
                _uiState.update { it.copy(startingRemoteSession = false, remoteSessionStartedId = result.id) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(startingRemoteSession = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun runGoalCommand(command: GoalCommand, text: String) {
        val pathName = selectedProjectName.value ?: return
        if (_uiState.value.startingGoalCommand || text.isBlank()) return
        _uiState.update { it.copy(startingGoalCommand = true, error = null) }
        viewModelScope.launch {
            try {
                val accepted = api.runAgent(null, pathName, command.buildCommand(text), null)
                _uiState.update { it.copy(startingGoalCommand = false, startedCommandId = accepted.id) }
                usageRepository.refresh()
            } catch (error: ApiException) {
                _uiState.update { it.copy(startingGoalCommand = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onStartedCommandConsumed() {
        _uiState.update { it.copy(startedCommandId = null) }
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
