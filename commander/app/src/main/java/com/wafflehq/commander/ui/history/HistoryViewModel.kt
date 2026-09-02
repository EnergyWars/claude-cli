package com.wafflehq.commander.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 3_000L
const val HISTORY_PAGE_SIZE = 5

data class HistoryUiState(
    val commands: List<CommandState> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/**
 * Verlauf-Liste, serverseitig in 5er-Schritten paginiert (`cl server`s `GET /commands/:pathName?limit=&offset=`).
 * Der Poll alle [POLL_INTERVAL_MS] fragt nicht immer nur die erste Seite ab, sondern das aktuell geladene
 * Fenster (`limit = commands.size`) und ersetzt `commands` 1:1 durch das Ergebnis - dadurch bleiben bereits
 * nachgeladene Seiten und die Scroll-Position erhalten (kein Reset auf 5 Eintraege, kein Sprung), waehrend
 * Status-Aenderungen laufender Commands und neu hinzugekommene Eintraege ganz oben trotzdem live ankommen.
 * [loadMore] haengt jeweils eine weitere Seite an; ein Fehler bei einem Hintergrund-Poll oder beim Nachladen
 * ersetzt nur die Fehlermeldung, nie die bereits geladene Liste.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshLoadedWindow()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshLoadedWindow() {
        val windowSize = _uiState.value.commands.size.coerceAtLeast(HISTORY_PAGE_SIZE)
        try {
            val page = api.getCommands(pathName, limit = windowSize, offset = 0)
            _uiState.update {
                it.copy(commands = page.commands, loading = false, hasMore = page.hasMore, error = null)
            }
        } catch (error: ApiException) {
            _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.loadingMore || !current.hasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            try {
                val page = api.getCommands(pathName, limit = HISTORY_PAGE_SIZE, offset = current.commands.size)
                val existingIds = current.commands.mapTo(mutableSetOf(), CommandState::id)
                val appended = page.commands.filterNot { it.id in existingIds }
                _uiState.update {
                    it.copy(
                        commands = it.commands + appended,
                        loadingMore = false,
                        hasMore = page.hasMore,
                        error = null,
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loadingMore = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
