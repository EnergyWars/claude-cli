package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Ticket
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val TICKET_STATUS_FILTER_ALL = ""
val TICKET_STATUS_FILTERS = listOf(TICKET_STATUS_FILTER_ALL) + TICKET_STATUS_ORDER

data class TicketListUiState(
    val tickets: List<Ticket> = emptyList(),
    val statusFilterIndex: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val createText: String = "",
    val creating: Boolean = false,
    val createdTicketId: Int? = null,
)

@HiltViewModel
class TicketListViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(TicketListUiState())
    val uiState: StateFlow<TicketListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
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
        val text = _uiState.value.createText
        if (text.isBlank()) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val ticket = api.createTicket(pathName, text)
                _uiState.update { it.copy(creating = false, createText = "", createdTicketId = ticket.id) }
                refresh()
            } catch (error: ApiException) {
                _uiState.update { it.copy(creating = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun consumeCreatedTicket() {
        _uiState.update { it.copy(createdTicketId = null) }
    }
}
