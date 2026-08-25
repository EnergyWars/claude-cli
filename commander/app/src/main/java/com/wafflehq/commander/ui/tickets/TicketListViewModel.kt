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
    val createdTicketPathName: String? = null,
    val createdTicketId: Int? = null,
    val availablePaths: List<String> = emptyList(),
    val selectedPathIndex: Int = 0,
)

@HiltViewModel
class TicketListViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String? = savedStateHandle["pathName"]

    private val _uiState = MutableStateFlow(TicketListUiState())
    val uiState: StateFlow<TicketListUiState> = _uiState.asStateFlow()

    init {
        refresh()
        if (pathName == null) {
            loadAvailablePaths()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val status = TICKET_STATUS_FILTERS.getOrNull(_uiState.value.statusFilterIndex)?.ifEmpty { null }
                val tickets = if (pathName != null) {
                    api.listTickets(pathName, status).tickets
                } else {
                    api.listAllTickets(status).tickets
                }
                _uiState.update { it.copy(tickets = tickets, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    private fun loadAvailablePaths() {
        viewModelScope.launch {
            try {
                val paths = api.getManifest().paths.map { it.name }
                _uiState.update { it.copy(availablePaths = paths) }
            } catch (_: ApiException) {
                // Pfad-Auswahl bleibt leer, Erstellung ist dann nicht moeglich.
            }
        }
    }

    fun onStatusFilterSelected(index: Int) {
        _uiState.update { it.copy(statusFilterIndex = index) }
        refresh()
    }

    fun onPathSelected(index: Int) {
        _uiState.update { it.copy(selectedPathIndex = index) }
    }

    fun onCreateTextChange(value: String) {
        _uiState.update { it.copy(createText = value) }
    }

    fun createTicket() {
        val state = _uiState.value
        val text = state.createText
        val targetPath = pathName ?: state.availablePaths.getOrNull(state.selectedPathIndex)
        if (text.isBlank() || targetPath == null) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val ticket = api.createTicket(targetPath, text)
                _uiState.update {
                    it.copy(
                        creating = false,
                        createText = "",
                        createdTicketPathName = ticket.pathName,
                        createdTicketId = ticket.id,
                    )
                }
                refresh()
            } catch (error: ApiException) {
                _uiState.update { it.copy(creating = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun consumeCreatedTicket() {
        _uiState.update { it.copy(createdTicketPathName = null, createdTicketId = null) }
    }
}
