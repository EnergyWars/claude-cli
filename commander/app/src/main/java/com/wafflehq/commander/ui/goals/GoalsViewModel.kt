package com.wafflehq.commander.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.GoalListGroup
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val GOALS_POLL_INTERVAL_MS = 5_000L

data class GoalsUiState(
    val goalGroups: List<GoalListGroup> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val startingKey: String? = null,
    val startedCommandId: String? = null,
    val startedSessionId: String? = null,
    val expandedFolders: Set<String> = emptySet(),
)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val api: ClServerApi,
    private val usageRepository: UsageRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(GoalsUiState())
    val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val goalGroups = api.getGoals(pathName)
                _uiState.update { it.copy(goalGroups = goalGroups, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun toggleFolder(folder: String) {
        _uiState.update {
            val expanded = if (folder in it.expandedFolders) it.expandedFolders - folder else it.expandedFolders + folder
            it.copy(expandedFolders = expanded)
        }
    }

    fun startGoal(folder: String, fileName: String, interactive: Boolean = false) {
        val key = goalKey(folder, fileName)
        if (_uiState.value.startingKey != null) return

        _uiState.update { it.copy(startingKey = key, error = null) }
        viewModelScope.launch {
            try {
                if (interactive) {
                    val session = api.startGoalInteractive(pathName, folder, fileName)
                    _uiState.update { it.copy(startingKey = null, startedSessionId = session.id) }
                } else {
                    val accepted = api.startGoal(pathName, folder, fileName)
                    _uiState.update { it.copy(startingKey = null, startedCommandId = accepted.id) }
                    usageRepository.refresh()
                }
                reloadGoals()
            } catch (error: ApiException) {
                _uiState.update { it.copy(startingKey = null, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    private suspend fun reloadGoals() {
        try {
            val goalGroups = api.getGoals(pathName)
            _uiState.update { it.copy(goalGroups = goalGroups) }
        } catch (_: ApiException) {
        }
    }

    suspend fun pollGoals() {
        while (currentCoroutineContext().isActive) {
            delay(GOALS_POLL_INTERVAL_MS)
            reloadGoals()
        }
    }

    fun consumeStartedSession() {
        _uiState.update { it.copy(startedSessionId = null) }
    }

    fun consumeStartedCommand() {
        _uiState.update { it.copy(startedCommandId = null) }
    }
}

fun goalKey(folder: String, fileName: String): String = "$folder/$fileName"
