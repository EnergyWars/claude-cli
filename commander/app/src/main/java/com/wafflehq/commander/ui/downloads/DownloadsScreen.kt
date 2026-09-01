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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.wafflehq.commander.data.api.HOSTED_TYPE_FILE
import com.wafflehq.commander.data.api.HostedFileEntry
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.isApkFileName
import com.wafflehq.commander.ui.components.ApkActionDialog
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val pendingInstalls by viewModel.pendingInstalls.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDeleteDownloadedFile by remember { mutableStateOf(false) }

    val downloadedFile = state.downloadedFile
    LaunchedEffect(downloadedFile) {
        downloadedFile?.let { file ->
            if (!isApkFileName(file.name)) {
                context.startActivity(viewModel.openOrInstallIntent(file))
                viewModel.consumeDownloadedFile()
            }
        }
    }

    if (downloadedFile != null && isApkFileName(downloadedFile.name)) {
        ApkActionDialog(
            fileName = downloadedFile.name,
            onInstall = {
                context.startActivity(viewModel.installIntent(downloadedFile))
                viewModel.consumeDownloadedFile()
            },
            onShare = {
                context.startActivity(viewModel.shareApkIntent(downloadedFile))
                viewModel.consumeDownloadedFile()
            },
            onDelete = { pendingDeleteDownloadedFile = true },
            onDismiss = { viewModel.consumeDownloadedFile() },
        )
    }

    if (pendingDeleteDownloadedFile && downloadedFile != null) {
        AppConfirmDialog(
            title = stringResource(R.string.apk_action_delete_confirm_title),
            body = stringResource(R.string.apk_action_delete_confirm_body, downloadedFile.name),
            confirmText = stringResource(R.string.apk_action_delete_confirm_confirm),
            dismissText = stringResource(R.string.label_cancel),
            onConfirm = {
                viewModel.deleteDownloadedFile()
                pendingDeleteDownloadedFile = false
            },
            onDismiss = { pendingDeleteDownloadedFile = false },
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.downloads_title),
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
            AppButton(
                text = stringResource(R.string.download_history_button),
                role = AppRole.Neutral,
                variant = ButtonVariant.Outlined,
                onClick = onOpenHistory,
            )

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.hosted.isEmpty()) {
                Text(
                    text = stringResource(R.string.downloads_files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            state.hosted.forEach { entry ->
                HostedEntryRow(
                    entry = entry,
                    expandedFiles = state.expandedHostedFiles[entry.name],
                    downloadingName = state.downloadingName,
                    downloadStatus = downloadStatus,
                    pendingInstalls = pendingInstalls,
                    onToggleExpand = { viewModel.toggleExpandHosted(entry.name) },
                    onDownload = { viewModel.downloadEntry(entry.name) },
                    onDownloadNested = { fileName -> viewModel.downloadNestedFile(entry.name, fileName) },
                )
            }
        }
    }
}

@Composable
private fun HostedEntryRow(
    entry: ManifestHostedEntry,
    expandedFiles: List<HostedFileEntry>?,
    downloadingName: String?,
    downloadStatus: DownloadStatus?,
    pendingInstalls: Set<String>,
    onToggleExpand: () -> Unit,
    onDownload: () -> Unit,
    onDownloadNested: (String) -> Unit,
) {
    val isFile = entry.type == HOSTED_TYPE_FILE
    val isDownloading = isFile && downloadingName == entry.name
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                Icon(
                    imageVector = if (isFile) Icons.AutoMirrored.Outlined.InsertDriveFile else Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = AppTheme.colors.onSurfaceVariant,
                )
                Text(entry.name, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
                if (isFile) {
                    if (!isDownloading) {
                        val icon = if (pendingInstalls.contains(entry.name)) Icons.Outlined.InstallMobile else Icons.Outlined.Download
                        AppIconButton(icon = icon, contentDescription = entry.name, role = AppRole.Primary, onClick = onDownload)
                    }
                } else {
                    AppIconButton(icon = Icons.Outlined.ChevronRight, contentDescription = entry.name, role = AppRole.Neutral, onClick = onToggleExpand)
                }
            }
            val entryTimestamp = entry.timestamp
            if (isFile && entryTimestamp != null) {
                Text(
                    text = stringResource(R.string.apk_build_time, formatTimestamp(entryTimestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurface,
                )
            }
            if (isDownloading) {
                DownloadProgressIndicator(status = downloadStatus, modifier = Modifier.padding(top = AppSpacing.sm))
            }
            if (expandedFiles != null) {
                Column(
                    modifier = Modifier.padding(top = AppSpacing.sm, start = AppSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    if (expandedFiles.isEmpty()) {
                        Text(stringResource(R.string.downloads_files_empty), color = AppTheme.colors.onSurfaceVariant)
                    }
                    expandedFiles.forEach { file ->
                        val isNestedDownloading = downloadingName == file.name
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                            ) {
                                Text(file.name, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
                                if (!isNestedDownloading) {
                                    val icon = if (pendingInstalls.contains(file.name)) Icons.Outlined.InstallMobile else Icons.Outlined.Download
                                    AppIconButton(
                                        icon = icon,
                                        contentDescription = file.name,
                                        role = AppRole.Primary,
                                        onClick = { onDownloadNested(file.name) },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.apk_build_time, formatTimestamp(file.timestamp)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.onSurface,
                            )
                            if (isNestedDownloading) {
                                DownloadProgressIndicator(status = downloadStatus)
                            }
                        }
                    }
                }
            }
        }
    }
}
