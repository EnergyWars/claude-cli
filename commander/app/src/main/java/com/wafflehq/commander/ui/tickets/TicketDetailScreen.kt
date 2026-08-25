package com.wafflehq.commander.ui.tickets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun TicketDetailScreen(
    onBack: () -> Unit,
    viewModel: TicketDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val statusLabels = TICKET_STATUS_ORDER.map { ticketStatusLabel(it) }

    SettingsScaffold(
        title = stringResource(R.string.ticket_detail_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
                return@Column
            }

            val ticket = state.ticket
            if (ticket != null) {
                Text(
                    text = ticket.pathName,
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                val ipLabel = ticket.ipAddress ?: stringResource(R.string.ticket_detail_ip_unknown)
                Text(
                    text = stringResource(R.string.ticket_detail_ip_label, ipLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            AppTextField(
                value = state.originalRequestInput,
                onValueChange = viewModel::onOriginalRequestChange,
                label = stringResource(R.string.ticket_detail_original_request_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            )
            AppTextField(
                value = state.summaryInput,
                onValueChange = viewModel::onSummaryChange,
                label = stringResource(R.string.ticket_detail_summary_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            AppTextField(
                value = state.claudeInstructionInput,
                onValueChange = viewModel::onClaudeInstructionChange,
                label = stringResource(R.string.ticket_detail_claude_instruction_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            AppTextField(
                value = state.categoryInput,
                onValueChange = viewModel::onCategoryChange,
                label = stringResource(R.string.ticket_detail_category_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsDropdownField(
                label = stringResource(R.string.ticket_detail_status_label),
                value = statusLabels.getOrElse(state.statusIndex) { statusLabels[0] },
                options = statusLabels,
                selectedIndex = state.statusIndex,
                onSelect = viewModel::onStatusSelected,
            )

            AppButton(
                text = stringResource(R.string.ticket_detail_save),
                role = AppRole.Primary,
                onClick = viewModel::save,
                enabled = !state.saving &&
                    state.originalRequestInput.isNotBlank() &&
                    state.summaryInput.isNotBlank() &&
                    state.claudeInstructionInput.isNotBlank() &&
                    state.categoryInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                text = stringResource(R.string.ticket_detail_delete),
                role = AppRole.Error,
                variant = ButtonVariant.Outlined,
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDeleteConfirm) {
        AppConfirmDialog(
            title = stringResource(R.string.ticket_detail_delete_confirm_title),
            body = stringResource(R.string.ticket_detail_delete_confirm_body),
            confirmText = stringResource(R.string.ticket_detail_delete),
            dismissText = stringResource(R.string.label_cancel),
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
