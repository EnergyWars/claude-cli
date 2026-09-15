package com.wafflehq.commander.ui.metrics

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
import com.wafflehq.commander.data.api.SystemMetricSample
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppLineChart
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun SystemMetricsScreen(
    onBack: () -> Unit,
    viewModel: SystemMetricsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.system_metrics_title),
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
            } else {
                val data = state.data
                if (data != null) {
                    val metrics = data.metrics
                    val latest = metrics.lastOrNull()
                    CpuCard(metrics, latest)
                    RamCard(metrics, latest)
                    AppButton(
                        text = stringResource(R.string.system_metrics_refresh),
                        role = AppRole.Primary,
                        variant = ButtonVariant.Tonal,
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CpuCard(metrics: List<SystemMetricSample>, latest: SystemMetricSample?) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.system_metrics_cpu_title),
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = latest?.let { "${it.cpuPercent.toInt()}%" } ?: stringResource(R.string.system_metrics_no_data),
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.onSurface,
            )
            AppLineChart(
                values = metrics.map { it.cpuPercent.toFloat() },
                emptyLabel = stringResource(R.string.system_metrics_empty),
                role = AppRole.Primary,
                valueFormatter = { "${it.toInt()}%" },
            )
        }
    }
}

@Composable
private fun RamCard(metrics: List<SystemMetricSample>, latest: SystemMetricSample?) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.system_metrics_ram_title),
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = latest?.let {
                    stringResource(
                        R.string.system_metrics_ram_usage,
                        formatBytesAsGiB(it.memTotalBytes - it.memFreeBytes),
                        formatBytesAsGiB(it.memTotalBytes),
                    )
                } ?: stringResource(R.string.system_metrics_no_data),
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.onSurface,
            )
            AppLineChart(
                values = metrics.map { it.memUsedPercent.toFloat() },
                emptyLabel = stringResource(R.string.system_metrics_empty),
                role = AppRole.Secondary,
                valueFormatter = { "${it.toInt()}%" },
            )
        }
    }
}
