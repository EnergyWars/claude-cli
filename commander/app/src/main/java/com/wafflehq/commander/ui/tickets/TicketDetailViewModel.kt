package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Ticket
import com.wafflehq.commander.data.api.TicketPatchRequest
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
)

@HiltViewModel
class TicketDetailViewModel @Inject constructor(
    private val api: ClServerApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pathName: String = checkNotNull(savedStateHandle["pathName"])
    private val id: Int = checkNotNull(savedStateHandle["id"])

    private val _uiState = MutableStateFlow(TicketDetailUiState())
    val uiState: StateFlow<TicketDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
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

    private fun TicketDetailUiState.applying(ticket: Ticket): TicketDetailUiState = copy(
        ticket = ticket,
        originalRequestInput = ticket.originalRequest,
        summaryInput = ticket.summary,
        claudeInstructionInput = ticket.claudeInstruction,
        categoryInput = ticket.category,
        statusIndex = TICKET_STATUS_ORDER.indexOf(ticket.status).coerceAtLeast(0),
    )
}
