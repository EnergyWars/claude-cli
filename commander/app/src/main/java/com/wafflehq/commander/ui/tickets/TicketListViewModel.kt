package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
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
val TICKET_STATUS_FILTERS = listOf(TICKET_STATUS_FILTER_ALL) + TICKET_STATUS_ORDER

private const val PENDING_TICKET_POLL_INTERVAL_MS = 1_000L

data class PendingTicketCreation(
    val tempId: Int,
)

data class TicketListUiState(
    val tickets: List<Ticket> = emptyList(),
    val statusFilterIndex: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val createText: String = "",
    val pendingCreations: List<PendingTicketCreation> = emptyList(),
)

@HiltViewModel
class TicketListViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(TicketListUiState())
    val uiState: StateFlow<TicketListUiState> = _uiState.asStateFlow()

    private var nextTempId = 1
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
        val state = _uiState.value
        val text = state.createText
        if (text.isBlank()) return

        val tempId = nextTempId++
        _uiState.update {
            it.copy(
                createText = "",
                error = null,
                pendingCreations = it.pendingCreations + PendingTicketCreation(tempId),
            )
        }
        refresh(showLoading = false)
        ensurePendingPoll()

        viewModelScope.launch {
            try {
                val ticket = api.createTicket(pathName, text)
                _uiState.update {
                    it.copy(
                        pendingCreations = it.pendingCreations.filterNot { pending -> pending.tempId == tempId },
                        tickets = listOf(ticket) + it.tickets.filterNot { existing -> existing.id == ticket.id },
                    )
                }
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(
                        pendingCreations = it.pendingCreations.filterNot { pending -> pending.tempId == tempId },
                        error = error.message ?: "Unbekannter Fehler.",
                    )
                }
            }
        }
    }

    /** Solange ein Ticket noch erstellt wird, jede Sekunde neu laden, damit die Liste aktuell bleibt. */
    private fun ensurePendingPoll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (_uiState.value.pendingCreations.isNotEmpty()) {
                delay(PENDING_TICKET_POLL_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }
}
