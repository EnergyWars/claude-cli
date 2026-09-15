package com.wafflehq.commander.ui.schedulers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

private data class PendingTrigger(val name: String, val kind: SchedulerKind)

@Composable
fun SchedulersScreen(
    onBack: () -> Unit,
    onCommandStarted: (commandId: String, pathName: String) -> Unit,
    viewModel: SchedulersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingTrigger by remember { mutableStateOf<PendingTrigger?>(null) }

    LaunchedEffect(state.startedCommandId) {
        state.startedCommandId?.let { id ->
            onCommandStarted(id, viewModel.pathName)
            viewModel.consumeStartedCommand()
        }
    }

    pendingTrigger?.let { trigger ->
        AppConfirmDialog(
            title = stringResource(R.string.schedulers_trigger_confirm_title),
            body = stringResource(R.string.schedulers_trigger_confirm_body, trigger.name),
            confirmText = stringResource(R.string.schedulers_trigger_confirm_confirm),
            dismissText = stringResource(R.string.label_cancel),
            confirmRole = AppRole.Primary,
            onConfirm = {
                viewModel.trigger(trigger.name, trigger.kind)
                pendingTrigger = null
            },
            onDismiss = { pendingTrigger = null },
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.schedulers_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.schedulers.isEmpty() && state.scriptSchedulers.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedulers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            state.schedulers.forEach { scheduler ->
                SchedulerRow(
                    name = scheduler.name,
                    description = scheduler.description,
                    cron = scheduler.cron,
                    enabled = scheduler.enabled,
                    triggering = state.triggeringName == scheduler.name,
                    updatingEnabled = state.updatingName == scheduler.name,
                    onClick = { pendingTrigger = PendingTrigger(scheduler.name, SchedulerKind.AGENT) },
                    onEnabledChange = { viewModel.setEnabled(scheduler.name, SchedulerKind.AGENT, it) },
                )
            }
            state.scriptSchedulers.forEach { scheduler ->
                SchedulerRow(
                    name = scheduler.name,
                    description = scheduler.description,
                    cron = scheduler.cron,
                    enabled = scheduler.enabled,
                    triggering = state.triggeringName == scheduler.name,
                    updatingEnabled = state.updatingName == scheduler.name,
                    onClick = { pendingTrigger = PendingTrigger(scheduler.name, SchedulerKind.SCRIPT) },
                    onEnabledChange = { viewModel.setEnabled(scheduler.name, SchedulerKind.SCRIPT, it) },
                )
            }
        }
    }
}

@Composable
private fun SchedulerRow(
    name: String,
    description: String,
    cron: String,
    enabled: Boolean,
    triggering: Boolean,
    updatingEnabled: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                }
                Text(
                    text = stringResource(R.string.schedulers_cron_label, cron),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.schedulers_enabled_label else R.string.schedulers_disabled_label,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        AppTheme.colors.onSurfaceVariant
                    } else {
                        AppTheme.colors.forRole(AppRole.Warning).accent
                    },
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = !updatingEnabled,
            )
            if (triggering) {
                CircularProgressIndicator(modifier = Modifier.padding(AppSpacing.sm))
            } else {
                AppIconButton(
                    icon = Icons.Outlined.PlayArrow,
                    contentDescription = name,
                    role = AppRole.Primary,
                    onClick = onClick,
                )
            }
        }
    }
}
