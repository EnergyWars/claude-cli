package com.wafflehq.commander.ui.costs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.CostEntry
import com.wafflehq.commander.data.api.CostProject
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun TokenCostsScreen(
    onBack: () -> Unit,
    onOpenCommand: (id: String, pathName: String) -> Unit,
    viewModel: TokenCostsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.token_costs_title),
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
            } else {
                val data = state.data
                if (data != null) {
                    TotalsCard(data.totalCostUsd, data.totalInputTokens, data.totalOutputTokens, data.totalCacheCreationInputTokens, data.totalCacheReadInputTokens)
                    AppButton(
                        text = stringResource(R.string.token_costs_refresh),
                        role = AppRole.Primary,
                        variant = ButtonVariant.Tonal,
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (data.projects.isEmpty()) {
                        Text(
                            text = stringResource(R.string.token_costs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    } else {
                        data.projects.forEach { project ->
                            ProjectCostCard(project = project, onOpenCommand = onOpenCommand)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(
    totalCostUsd: Double,
    totalInputTokens: Long,
    totalOutputTokens: Long,
    totalCacheCreationInputTokens: Long,
    totalCacheReadInputTokens: Long,
) {
    AppCard(role = AppRole.Primary, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Text(
                text = formatUsd(totalCostUsd),
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.onSurface,
            )
            Text(
                text = stringResource(R.string.token_costs_input_tokens, formatTokenCount(totalInputTokens)),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.token_costs_output_tokens, formatTokenCount(totalOutputTokens)),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.token_costs_cache_creation_tokens,
                    formatTokenCount(totalCacheCreationInputTokens),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.token_costs_cache_read_tokens, formatTokenCount(totalCacheReadInputTokens)),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectCostCard(project: CostProject, onOpenCommand: (id: String, pathName: String) -> Unit) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = project.pathName,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatUsd(project.totalCostUsd),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurface,
                )
            }
            project.entries.forEach { entry ->
                CostEntryRow(entry = entry, onClick = { onOpenCommand(entry.id, project.pathName) })
            }
        }
    }
}

@Composable
private fun CostEntryRow(entry: CostEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTimestamp(entry.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
        Text(
            text = formatUsd(entry.costUsd),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}
