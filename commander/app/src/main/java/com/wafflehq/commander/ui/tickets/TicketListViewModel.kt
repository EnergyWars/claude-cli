package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.TICKET_STATUS_GENERATING
import com.wafflehq.commander.data.api.Ticket
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val TICKET_STATUS_FILTER_ALL = ""
val TICKET_STATUS_FILTERS = listOf(TICKET_STATUS_FILTER_ALL) + TICKET_STATUS_FILTER_ORDER

private const val GENERATING_TICKET_POLL_INTERVAL_MS = 1_000L

data class TicketListUiState(
    val tickets: List<Ticket> = emptyList(),
    val statusFilterIndex: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val createText: String = "",
)

@HiltViewModel
class TicketListViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(TicketListUiState())
    val uiState: StateFlow<TicketListUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
    }

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _uiState.update { it.copy(loading = true, error = null) }
            try {
                val status = TICKET_STATUS_FILTERS.getOrNull(_uiState.value.statusFilterIndex)?.ifEmpty { null }
                val tickets = api.listTickets(pathName, status).tickets
                _uiState.update { it.copy(tickets = tickets, loading = false) }
                ensureGeneratingPoll()
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onStatusFilterSelected(index: Int) {
        _uiState.update { it.copy(statusFilterIndex = index) }
        refresh()
    }

    fun onCreateTextChange(value: String) {
        _uiState.update { it.copy(createText = value) }
    }

    fun createTicket() {
        val text = _uiState.value.createText
        if (text.isBlank()) return

        _uiState.update { it.copy(createText = "", error = null) }
        viewModelScope.launch {
            try {
                api.createTicket(pathName, text)
                refresh(showLoading = false)
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    /** Solange ein Ticket im Status "generating" in der Liste steht, jede Sekunde neu laden. */
    private fun ensureGeneratingPoll() {
        if (pollJob?.isActive == true) return
        if (_uiState.value.tickets.none { it.status == TICKET_STATUS_GENERATING }) return
        pollJob = viewModelScope.launch {
            while (uiState.value.tickets.any { it.status == TICKET_STATUS_GENERATING }) {
                delay(GENERATING_TICKET_POLL_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }
}
