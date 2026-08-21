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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing

@Composable
fun TicketDetailScreen(
    onBack: () -> Unit,
    viewModel: TicketDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
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

            AppTextField(
                value = state.titleInput,
                onValueChange = viewModel::onTitleChange,
                label = stringResource(R.string.ticket_detail_title_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = state.descriptionInput,
                onValueChange = viewModel::onDescriptionChange,
                label = stringResource(R.string.ticket_detail_description_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            AppTextField(
                value = state.taskInput,
                onValueChange = viewModel::onTaskChange,
                label = stringResource(R.string.ticket_detail_task_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
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
                    state.titleInput.isNotBlank() &&
                    state.descriptionInput.isNotBlank() &&
                    state.taskInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                text = stringResource(R.string.ticket_detail_delete),
                role = AppRole.Error,
                variant = ButtonVariant.Outlined,
                onClick = viewModel::delete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
