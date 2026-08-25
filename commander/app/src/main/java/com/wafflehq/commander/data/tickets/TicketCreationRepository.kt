package com.wafflehq.commander.data.tickets

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PendingTicketCreation(
    val tempId: Int,
    val pathName: String,
)

/**
 * Hält laufende Ticket-Erstellungen in einem app-weiten Scope, nicht im ViewModel-Scope,
 * damit die "Lädt Ticket…"-Zeile beim Verlassen und erneuten Betreten des Ticket-Screens
 * erhalten bleibt, bis der Request durch ist.
 */
@Singleton
class TicketCreationRepository @Inject constructor(
    private val api: ClServerApi,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var nextTempId = 1

    private val _pendingCreations = MutableStateFlow<List<PendingTicketCreation>>(emptyList())
    val pendingCreations: StateFlow<List<PendingTicketCreation>> = _pendingCreations.asStateFlow()

    fun create(pathName: String, text: String) {
        val tempId = nextTempId++
        _pendingCreations.update { it + PendingTicketCreation(tempId, pathName) }
        scope.launch {
            try {
                api.createTicket(pathName, text)
            } catch (_: ApiException) {
                // Wird beim nächsten Refresh sichtbar, falls es doch geklappt hat; ansonsten bleibt es einfach weg.
            } finally {
                _pendingCreations.update { list -> list.filterNot { it.tempId == tempId } }
            }
        }
    }

    fun dismiss(tempId: Int) {
        _pendingCreations.update { it.filterNot { pending -> pending.tempId == tempId } }
    }
}
