package com.wafflehq.appgetter.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.appgetter.R
import com.wafflehq.appgetter.data.api.CollectedFile
import com.wafflehq.appgetter.data.install.DownloadStatus
import com.wafflehq.appgetter.ui.components.ApkActionDialog
import com.wafflehq.appgetter.ui.components.AppBanner
import com.wafflehq.appgetter.ui.components.AppButton
import com.wafflehq.appgetter.ui.components.AppCard
import com.wafflehq.appgetter.ui.components.AppIconButton
import com.wafflehq.appgetter.ui.feedback.FeedbackDialog
import com.wafflehq.appgetter.ui.feedback.FeedbackViewModel
import com.wafflehq.appgetter.ui.navigation.hiltViewModel
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppSpacing
import com.wafflehq.appgetter.ui.theme.AppTheme

@Composable
fun CollectionsScreen(
    onOpenSettings: () -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel(),
    feedbackViewModel: FeedbackViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val downloadedTimestamps by viewModel.downloadedTimestamps.collectAsStateWithLifecycle()
    val feedbackState by feedbackViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleLarge,
                color = AppTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            AppIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.label_settings),
                role = AppRole.Neutral,
                onClick = onOpenSettings,
            )
        }

        val error = uiState.error
        if (error != null) {
            AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
        }

        val feedbackError = feedbackState.error
        if (feedbackError != null) {
            AppBanner(title = stringResource(R.string.setup_error_title), body = feedbackError, role = AppRole.Error)
        }

        when (val state = uiState.state) {
            CollectionsState.Scanning -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.home_scanning), color = AppTheme.colors.onSurfaceVariant)
                }
            }

            CollectionsState.NotFound -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(stringResource(R.string.home_not_found), color = AppTheme.colors.onSurfaceVariant)
                    AppButton(
                        text = stringResource(R.string.home_retry),
                        role = AppRole.Primary,
                        onClick = viewModel::scan,
                    )
                }
            }

            is CollectionsState.Found -> if (state.files.isEmpty()) {
                Text(stringResource(R.string.home_empty), color = AppTheme.colors.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    items(state.files, key = { it.name }) { file ->
                        CollectedFileRow(
                            file = file,
                            downloadState = downloadState(file, downloadedTimestamps),
                            isDownloading = uiState.downloadingFileName == file.name,
                            enabled = uiState.downloadingFileName == null,
                            downloadStatus = downloadStatus,
                            onInstall = { viewModel.downloadAndInstall(file) },
                            onFeedback = { feedbackViewModel.open(file.name, feedbackContext(file)) },
                        )
                    }
                }
            }
        }
    }

    val openSection = feedbackState.openSection
    if (openSection != null) {
        FeedbackDialog(
            section = openSection,
            text = feedbackState.text,
            onTextChange = feedbackViewModel::onTextChange,
            onSend = feedbackViewModel::send,
            onDismiss = feedbackViewModel::dismiss,
        )
    }

    val installFile = uiState.installFile
    if (installFile != null) {
        ApkActionDialog(
            fileName = installFile.name,
            onInstall = {
                context.startActivity(viewModel.installIntent(installFile))
                viewModel.resolveInstallFile(installFile)
            },
            onShare = {
                context.startActivity(viewModel.shareIntent(installFile))
                viewModel.resolveInstallFile(installFile)
            },
            onDismiss = { viewModel.consumeInstallFile() },
        )
    }
}

@Composable
private fun CollectedFileRow(
    file: CollectedFile,
    downloadState: DownloadState,
    isDownloading: Boolean,
    enabled: Boolean,
    downloadStatus: DownloadStatus?,
    onInstall: () -> Unit,
    onFeedback: () -> Unit,
) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                    if (!isDownloading) {
                        Text(
                            text = formatTimestamp(file.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                        when (downloadState) {
                            DownloadState.UP_TO_DATE -> Text(
                                text = stringResource(R.string.home_download_up_to_date),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.success.accent,
                            )
                            DownloadState.UPDATE_AVAILABLE -> Text(
                                text = stringResource(R.string.home_download_update_available),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.warning.accent,
                            )
                            DownloadState.NOT_DOWNLOADED -> Unit
                        }
                    }
                }
                if (!isDownloading) {
                    AppIconButton(
                        icon = Icons.Outlined.Feedback,
                        contentDescription = stringResource(R.string.feedback_open),
                        role = AppRole.Neutral,
                        onClick = onFeedback,
                    )
                    AppIconButton(
                        icon = Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.home_install),
                        role = AppRole.Primary,
                        enabled = enabled,
                        onClick = onInstall,
                    )
                }
            }
            if (isDownloading) {
                DownloadProgressIndicator(status = downloadStatus, modifier = Modifier.padding(top = AppSpacing.sm))
            }
        }
    }
}
