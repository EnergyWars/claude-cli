package com.wafflehq.commander.ui.tickets

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.TICKET_STATUS_CLOSED
import com.wafflehq.commander.data.api.TICKET_STATUS_IN_PROGRESS
import com.wafflehq.commander.data.api.TICKET_STATUS_OPEN
import com.wafflehq.commander.ui.theme.AppRole

val TICKET_STATUS_ORDER = listOf(TICKET_STATUS_OPEN, TICKET_STATUS_IN_PROGRESS, TICKET_STATUS_CLOSED)

fun ticketStatusRole(status: String): AppRole = when (status) {
    TICKET_STATUS_OPEN -> AppRole.Warning
    TICKET_STATUS_IN_PROGRESS -> AppRole.Primary
    TICKET_STATUS_CLOSED -> AppRole.Success
    else -> AppRole.Neutral
}

@Composable
fun ticketStatusLabel(status: String): String = when (status) {
    TICKET_STATUS_OPEN -> stringResource(R.string.ticket_status_open)
    TICKET_STATUS_IN_PROGRESS -> stringResource(R.string.ticket_status_in_progress)
    TICKET_STATUS_CLOSED -> stringResource(R.string.ticket_status_closed)
    else -> status
}
