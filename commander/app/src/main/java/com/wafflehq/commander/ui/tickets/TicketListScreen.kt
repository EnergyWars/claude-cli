package com.wafflehq.commander.ui.tickets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.Ticket
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppStatusPill
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun TicketListScreen(
    onBack: () -> Unit,
    onOpenTicket: (pathName: String, id: Int) -> Unit,
    viewModel: TicketListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdTicketId) {
        state.createdTicketId?.let { id ->
            onOpenTicket(viewModel.pathName, id)
            viewModel.consumeCreatedTicket()
        }
    }

    val filterLabels = listOf(stringResource(R.string.tickets_filter_all)) +
        TICKET_STATUS_ORDER.map { ticketStatusLabel(it) }

    SettingsScaffold(
        title = stringResource(R.string.tickets_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            AppTextField(
                value = state.createText,
                onValueChange = viewModel::onCreateTextChange,
                label = stringResource(R.string.tickets_create_text_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                text = stringResource(R.string.tickets_create_submit),
                role = AppRole.Primary,
                onClick = viewModel::createTicket,
                enabled = !state.creating && state.createText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsDropdownField(
                label = stringResource(R.string.tickets_filter_label),
                value = filterLabels.getOrElse(state.statusFilterIndex) { filterLabels[0] },
                options = filterLabels,
                selectedIndex = state.statusFilterIndex,
                onSelect = viewModel::onStatusFilterSelected,
            )

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.tickets.isEmpty()) {
                Text(
                    text = stringResource(R.string.tickets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                items(state.tickets, key = { it.id }) { ticket ->
                    TicketRow(ticket = ticket, onClick = { onOpenTicket(viewModel.pathName, ticket.id) })
                }
            }
        }
    }
}

@Composable
private fun TicketRow(ticket: Ticket, onClick: () -> Unit) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text(ticket.title, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                Text(
                    text = ticket.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppStatusPill(text = ticketStatusLabel(ticket.status), role = ticketStatusRole(ticket.status))
        }
    }
}
