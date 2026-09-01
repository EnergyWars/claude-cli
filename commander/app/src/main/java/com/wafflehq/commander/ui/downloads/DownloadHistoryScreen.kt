package com.wafflehq.commander.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.download.DownloadVersion
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun DownloadHistoryScreen(
    onBack: () -> Unit,
    viewModel: DownloadHistoryViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<Pair<String, DownloadVersion>?>(null) }

    pendingDelete?.let { (identity, version) ->
        AppConfirmDialog(
            title = stringResource(R.string.apk_action_delete_confirm_title),
            body = stringResource(R.string.apk_action_delete_confirm_body, fileNameOf(version)),
            confirmText = stringResource(R.string.apk_action_delete_confirm_confirm),
            dismissText = stringResource(R.string.label_cancel),
            onConfirm = {
                viewModel.delete(identity, version)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.download_history_title),
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
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.download_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
            groups.forEach { group ->
                AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text(group.identity, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                        group.versions.forEach { version ->
                            DownloadHistoryVersionRow(
                                version = version,
                                onOpen = { context.startActivity(viewModel.openOrInstallIntent(version)) },
                                onDelete = { pendingDelete = group.identity to version },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadHistoryVersionRow(
    version: DownloadVersion,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            val timestamp = version.timestamp
            if (timestamp != null) {
                Text(
                    text = stringResource(R.string.apk_build_time, formatTimestamp(timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.download_history_downloaded_at, formatTimestamp(version.downloadedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
        AppIconButton(
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = stringResource(R.string.download_history_open),
            role = AppRole.Primary,
            onClick = onOpen,
        )
        AppIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.apk_action_delete),
            role = AppRole.Error,
            onClick = onDelete,
        )
    }
}

private fun fileNameOf(version: DownloadVersion): String = version.filePath.substringAfterLast('/')
