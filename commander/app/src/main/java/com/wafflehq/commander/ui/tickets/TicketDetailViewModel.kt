package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ManifestAgent
import com.wafflehq.commander.data.api.TICKET_STATUS_DONE
import com.wafflehq.commander.data.api.Ticket
import com.wafflehq.commander.data.api.TicketPatchRequest
import com.wafflehq.commander.data.api.agentNameOrNull
import com.wafflehq.commander.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TicketDetailUiState(
    val ticket: Ticket? = null,
    val originalRequestInput: String = "",
    val summaryInput: String = "",
    val claudeInstructionInput: String = "",
    val categoryInput: String = "",
    val statusIndex: Int = 0,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
    val agents: List<ManifestAgent> = emptyList(),
    val selectedAgentIndex: Int = 0,
    val playing: Boolean = false,
    val startedCommandId: String? = null,
)

@HiltViewModel
class TicketDetailViewModel @Inject constructor(
    private val api: ClServerApi,
    private val usageRepository: UsageRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pathName: String = checkNotNull(savedStateHandle["pathName"])
    private val id: Int = checkNotNull(savedStateHandle["id"])

    private val _uiState = MutableStateFlow(TicketDetailUiState())
    val uiState: StateFlow<TicketDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                val agents = api.getManifest().agents
                val defaultIndex = agents.indexOfFirst { it.command == "cl dev" }.let { if (it >= 0) it else 0 }
                _uiState.update { it.copy(agents = agents, selectedAgentIndex = defaultIndex) }
            } catch (_: ApiException) {
                // Agentenliste ist nur fuer den Play-Button noetig - kein Fehler in der Haupt-Statusanzeige.
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val ticket = api.getTicket(pathName, id)
                _uiState.update { it.copy(loading = false).applying(ticket) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onOriginalRequestChange(value: String) {
        _uiState.update { it.copy(originalRequestInput = value) }
    }

    fun onSummaryChange(value: String) {
        _uiState.update { it.copy(summaryInput = value) }
    }

    fun onClaudeInstructionChange(value: String) {
        _uiState.update { it.copy(claudeInstructionInput = value) }
    }

    fun onCategoryChange(value: String) {
        _uiState.update { it.copy(categoryInput = value) }
    }

    fun onStatusSelected(index: Int) {
        _uiState.update { it.copy(statusIndex = index) }
    }

    fun save() {
        val state = _uiState.value
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = api.updateTicket(
                    pathName,
                    id,
                    TicketPatchRequest(
                        originalRequest = state.originalRequestInput,
                        summary = state.summaryInput,
                        claudeInstruction = state.claudeInstructionInput,
                        category = state.categoryInput,
                        status = TICKET_STATUS_ORDER.getOrNull(state.statusIndex),
                    ),
                )
                _uiState.update { it.copy(saving = false).applying(updated) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(saving = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                api.deleteTicket(pathName, id)
                _uiState.update { it.copy(deleted = true) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onAgentSelected(index: Int) {
        _uiState.update { it.copy(selectedAgentIndex = index) }
    }

    fun play() {
        val state = _uiState.value
        val ticket = state.ticket ?: return
        val agent = state.agents.getOrNull(state.selectedAgentIndex)
        _uiState.update { it.copy(playing = true, error = null) }
        viewModelScope.launch {
            try {
                val accepted = api.runAgent(agent?.agentNameOrNull(), ticket.pathName, ticket.claudeInstruction, null)
                _uiState.update { it.copy(playing = false, startedCommandId = accepted.id) }
                usageRepository.refresh()
                try {
                    val updated = api.updateTicket(pathName, id, TicketPatchRequest(status = TICKET_STATUS_DONE))
                    _uiState.update { it.copy().applying(updated) }
                } catch (_: ApiException) {
                    // Befehl laeuft bereits - das Schliessen des Tickets kann manuell nachgeholt werden.
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(playing = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun consumeStartedCommand() {
        _uiState.update { it.copy(startedCommandId = null) }
    }

    private fun TicketDetailUiState.applying(ticket: Ticket): TicketDetailUiState = copy(
        ticket = ticket,
        originalRequestInput = ticket.originalRequest,
        summaryInput = ticket.summary,
        claudeInstructionInput = ticket.claudeInstruction,
        categoryInput = ticket.category,
        statusIndex = TICKET_STATUS_ORDER.indexOf(ticket.status).coerceAtLeast(0),
    )
}
