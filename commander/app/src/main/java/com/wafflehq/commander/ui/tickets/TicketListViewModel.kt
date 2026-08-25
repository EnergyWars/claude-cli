package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Ticket
import com.wafflehq.commander.data.tickets.PendingTicketCreation
import com.wafflehq.commander.data.tickets.TicketCreationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val TICKET_STATUS_FILTER_ALL = ""
val TICKET_STATUS_FILTERS = listOf(TICKET_STATUS_FILTER_ALL) + TICKET_STATUS_ORDER

private const val PENDING_TICKET_POLL_INTERVAL_MS = 1_000L

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
    private val creationRepository: TicketCreationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val localState = MutableStateFlow(TicketListUiState())

    val uiState: StateFlow<TicketListUiState> = combine(
        localState,
        creationRepository.pendingCreations,
    ) { state, pending ->
        state.copy(pendingCreations = pending.filter { it.pathName == pathName })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TicketListUiState())

    private var pollJob: Job? = null

    init {
        refresh()
        ensurePendingPoll()
    }

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) localState.update { it.copy(loading = true, error = null) }
            try {
                val status = TICKET_STATUS_FILTERS.getOrNull(localState.value.statusFilterIndex)?.ifEmpty { null }
                val tickets = api.listTickets(pathName, status).tickets
                localState.update { it.copy(tickets = tickets, loading = false) }
            } catch (error: ApiException) {
                localState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onStatusFilterSelected(index: Int) {
        localState.update { it.copy(statusFilterIndex = index) }
        refresh()
    }

    fun onCreateTextChange(value: String) {
        localState.update { it.copy(createText = value) }
    }

    fun createTicket() {
        val text = localState.value.createText
        if (text.isBlank()) return

        localState.update { it.copy(createText = "", error = null) }
        creationRepository.create(pathName, text)
        ensurePendingPoll()
    }

    fun onDismissPendingCreation(tempId: Int) {
        creationRepository.dismiss(tempId)
    }

    /** Solange ein Ticket noch erstellt wird, jede Sekunde neu laden, damit die Liste aktuell bleibt. */
    private fun ensurePendingPoll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (uiState.value.pendingCreations.isNotEmpty()) {
                delay(PENDING_TICKET_POLL_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }
}
