package com.wafflehq.commander.ui.stats

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
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.stats_title),
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
                val stats = state.stats
                if (stats != null) {
                    StatRow(
                        label = stringResource(R.string.stats_running_agents),
                        value = stats.runningAgents.toString(),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_agents_in_window, formatWindowHours(stats.windowHours)),
                        value = stats.agentsInWindow.toString(),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_last_debug_build),
                        value = stats.lastDebugBuildAt?.let(::formatTimestamp)
                            ?: stringResource(R.string.stats_no_build_yet),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_last_release_build),
                        value = stats.lastReleaseBuildAt?.let(::formatTimestamp)
                            ?: stringResource(R.string.stats_no_build_yet),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onSurface,
            )
        }
    }
}

private fun formatWindowHours(hours: Double): String =
    if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()
