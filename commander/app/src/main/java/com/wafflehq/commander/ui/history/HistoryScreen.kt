package com.wafflehq.commander.ui.history

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.api.isRetryable
import com.wafflehq.commander.data.api.retryAgentCommand
import com.wafflehq.commander.ui.command.COMMAND_STATUS_RUNNING
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.AppStatusPill
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

private const val STATUS_COMPLETED = "completed"
private const val STATUS_STOPPED = "stopped"

private fun historyStatusRole(status: String): AppRole = when (status) {
    STATUS_COMPLETED -> AppRole.Success
    COMMAND_STATUS_RUNNING -> AppRole.Warning
    STATUS_STOPPED -> AppRole.Neutral
    else -> AppRole.Error
}

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenCommand: (id: String) -> Unit,
    onRetryCommand: (agentCommand: String, prompt: String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.history_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.commands.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                items(state.commands, key = { it.id }) { command ->
                    HistoryRow(
                        command = command,
                        onClick = { onOpenCommand(command.id) },
                        onRetry = { onRetryCommand(command.retryAgentCommand(), command.command) },
                    )
                }
                if (state.hasMore || state.loadingMore) {
                    item(key = "load-more") {
                        Box(modifier = Modifier.fillMaxWidth().padding(AppSpacing.md), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(AppSpacing.xl))
                            } else {
                                AppButton(
                                    text = stringResource(R.string.history_load_more),
                                    role = AppRole.Primary,
                                    variant = ButtonVariant.Tonal,
                                    onClick = { viewModel.loadMore() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(command: CommandState, onClick: () -> Unit, onRetry: () -> Unit) {
    var commandExpanded by remember { mutableStateOf(false) }

    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                AppStatusPill(text = command.status, role = historyStatusRole(command.status))
                Text(
                    command.agent,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (command.isRetryable()) {
                    AppIconButton(
                        icon = Icons.Outlined.Replay,
                        contentDescription = stringResource(R.string.history_retry_action),
                        role = AppRole.Primary,
                        onClick = onRetry,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { commandExpanded = !commandExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.history_command_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (commandExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (commandExpanded) R.string.history_command_collapse else R.string.history_command_expand,
                    ),
                    tint = AppTheme.colors.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.animateContentSize()) {
                if (commandExpanded) {
                    Text(
                        text = command.command,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.history_row_meta,
                    formatTimestamp(command.createdAt),
                    formatDuration(command.createdAt, command.updatedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}
