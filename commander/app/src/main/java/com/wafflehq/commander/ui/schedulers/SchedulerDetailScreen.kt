package com.wafflehq.commander.ui.schedulers

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.SchedulerPathStatus
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import com.wafflehq.commander.ui.theme.GeistMono

@Composable
fun SchedulerDetailScreen(
    onBack: () -> Unit,
    viewModel: SchedulerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.scheduler_detail_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.lg,
                    top = AppSpacing.lg,
                    end = AppSpacing.lg,
                    bottom = AppSpacing.lg + AppSpacing.bottomSafeArea,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else {
                Text(viewModel.schedulerName, style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.onSurface)
                if (state.description.isNotBlank()) {
                    Text(state.description, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
                }
                Text(
                    text = stringResource(R.string.schedulers_cron_label, state.cron),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )

                AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(
                                if (viewModel.kind == SchedulerKind.SCRIPT) {
                                    R.string.scheduler_detail_script_label
                                } else {
                                    R.string.scheduler_detail_instructions_label
                                },
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = AppTheme.colors.onSurface,
                        )
                        Text(
                            text = state.executionText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.scheduler_detail_projects_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    state.pathStatuses.forEach { status ->
                        SchedulerPathRow(
                            status = status,
                            updating = state.updatingPathName == status.pathName,
                            onEnabledChange = { enabled -> viewModel.setEnabled(status.pathName, enabled) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulerPathRow(
    status: SchedulerPathStatus,
    updating: Boolean,
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
                Text(status.pathName, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                Text(
                    text = stringResource(
                        if (status.enabled) R.string.schedulers_enabled_label else R.string.schedulers_disabled_label,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.enabled) {
                        AppTheme.colors.onSurfaceVariant
                    } else {
                        AppTheme.colors.forRole(AppRole.Warning).accent
                    },
                )
            }
            Switch(
                checked = status.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !updating,
            )
        }
    }
}
