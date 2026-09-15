package com.wafflehq.commander.ui.schedulers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun AllSchedulersScreen(
    onBack: () -> Unit,
    onOpenDetail: (name: String, kind: SchedulerKind) -> Unit,
    viewModel: AllSchedulersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.all_schedulers_title),
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
                    text = stringResource(R.string.all_schedulers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            state.schedulers.forEach { scheduler ->
                AllSchedulersRow(
                    name = scheduler.name,
                    description = scheduler.description,
                    cron = scheduler.cron,
                    paths = scheduler.paths,
                    onClick = { onOpenDetail(scheduler.name, SchedulerKind.AGENT) },
                )
            }
            state.scriptSchedulers.forEach { scheduler ->
                AllSchedulersRow(
                    name = scheduler.name,
                    description = scheduler.description,
                    cron = scheduler.cron,
                    paths = scheduler.paths,
                    onClick = { onOpenDetail(scheduler.name, SchedulerKind.SCRIPT) },
                )
            }
        }
    }
}

@Composable
private fun AllSchedulersRow(
    name: String,
    description: String,
    cron: String,
    paths: List<String>,
    onClick: () -> Unit,
) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
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
                text = stringResource(R.string.all_schedulers_paths_label, paths.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}
