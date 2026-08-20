package com.wafflehq.commander.ui.pathdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.HOSTED_TYPE_FILE
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.api.PathCommandEntry
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun PathDetailScreen(
    onBack: () -> Unit,
    onStartAgent: (pathName: String) -> Unit,
    onCommandStarted: (commandId: String, pathName: String) -> Unit,
    viewModel: PathDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.startedCommandId) {
        state.startedCommandId?.let { id ->
            onCommandStarted(id, viewModel.pathName)
            viewModel.consumeStartedCommand()
        }
    }
    LaunchedEffect(state.downloadedFile) {
        state.downloadedFile?.let { file ->
            context.startActivity(viewModel.shareIntent(file))
            viewModel.consumeDownloadedFile()
        }
    }

    SettingsScaffold(
        title = viewModel.pathName,
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            AppButton(
                text = stringResource(R.string.path_detail_start_agent),
                role = AppRole.Primary,
                onClick = { onStartAgent(viewModel.pathName) },
                modifier = Modifier.fillMaxWidth(),
            )

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            }

            if (state.commands.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.path_detail_commands_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                state.commands.forEach { command ->
                    PathCommandRow(command = command, onClick = { viewModel.runCommand(command.key) })
                }
            }

            if (state.hosted.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.path_detail_files_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                state.hosted.forEach { entry ->
                    HostedEntryRow(
                        entry = entry,
                        expandedFiles = state.expandedHostedFiles[entry.name],
                        onToggleExpand = { viewModel.toggleExpandHosted(entry.name) },
                        onDownload = { viewModel.downloadEntry(entry.name) },
                        onDownloadNested = { fileName -> viewModel.downloadNestedFile(entry.name, fileName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PathCommandRow(command: PathCommandEntry, onClick: () -> Unit) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(command.displayName, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                Text(command.description, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            }
            AppIconButton(
                icon = Icons.Outlined.PlayArrow,
                contentDescription = command.displayName,
                role = AppRole.Primary,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun HostedEntryRow(
    entry: ManifestHostedEntry,
    expandedFiles: List<String>?,
    onToggleExpand: () -> Unit,
    onDownload: () -> Unit,
    onDownloadNested: (String) -> Unit,
) {
    val isFile = entry.type == HOSTED_TYPE_FILE
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
                    AppIconButton(icon = Icons.Outlined.Download, contentDescription = entry.name, role = AppRole.Primary, onClick = onDownload)
                } else {
                    AppIconButton(icon = Icons.Outlined.ChevronRight, contentDescription = entry.name, role = AppRole.Neutral, onClick = onToggleExpand)
                }
            }
            if (expandedFiles != null) {
                Column(
                    modifier = Modifier.padding(top = AppSpacing.sm, start = AppSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    if (expandedFiles.isEmpty()) {
                        Text(stringResource(R.string.path_detail_files_empty), color = AppTheme.colors.onSurfaceVariant)
                    }
                    expandedFiles.forEach { fileName ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                        ) {
                            Text(fileName, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
                            AppIconButton(
                                icon = Icons.Outlined.Download,
                                contentDescription = fileName,
                                role = AppRole.Primary,
                                onClick = { onDownloadNested(fileName) },
                            )
                        }
                    }
                }
            }
        }
    }
}
