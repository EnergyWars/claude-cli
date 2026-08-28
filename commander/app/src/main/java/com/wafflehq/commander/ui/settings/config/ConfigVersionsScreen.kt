package com.wafflehq.commander.ui.settings.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

private data class PendingActivation(val versionId: Int?, val label: String)

@Composable
fun ConfigVersionsScreen(
    onBack: () -> Unit,
    viewModel: ConfigVersionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingActivation?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.settings_config_versions_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.padding(AppSpacing.lg))
                return@Column
            }

            val embeddedLabel = stringResource(R.string.settings_config_versions_embedded)
            ConfigVersionRow(
                title = embeddedLabel,
                subtitle = null,
                active = state.activeVersionId == null,
                enabled = !state.switching,
                onClick = { pending = PendingActivation(null, embeddedLabel) },
            )
            state.versions.forEach { version ->
                val label = stringResource(R.string.settings_config_versions_entry, version.id)
                ConfigVersionRow(
                    title = label,
                    subtitle = formatTimestamp(version.createdAt),
                    active = state.activeVersionId == version.id,
                    enabled = !state.switching,
                    onClick = { pending = PendingActivation(version.id, label) },
                )
            }

            val error = state.error
            if (error != null) {
                AppBanner(
                    title = stringResource(R.string.setup_error_title),
                    body = error,
                    role = AppRole.Error,
                    modifier = Modifier.padding(AppSpacing.lg),
                )
            }

            val warning = state.warning
            if (warning != null) {
                AppBanner(
                    title = stringResource(R.string.settings_config_warning_title),
                    body = warning,
                    role = AppRole.Warning,
                    modifier = Modifier.padding(AppSpacing.lg),
                )
            }
        }
    }

    val target = pending
    if (target != null) {
        AppConfirmDialog(
            title = stringResource(R.string.settings_config_versions_confirm_title),
            body = stringResource(R.string.settings_config_versions_confirm_body, target.label),
            confirmText = stringResource(R.string.settings_config_versions_confirm_action),
            dismissText = stringResource(R.string.label_cancel),
            confirmRole = AppRole.Primary,
            onConfirm = {
                viewModel.activate(target.versionId)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ConfigVersionRow(
    title: String,
    subtitle: String?,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(enabled = enabled && !active, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (active) {
            Icon(imageVector = Icons.Outlined.Check, contentDescription = null, tint = colors.forRole(AppRole.Success).accent, modifier = Modifier.size(20.dp))
        }
    }
}
