package com.wafflehq.commander.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.AppStatusPill
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import com.wafflehq.commander.ui.theme.GeistMono

private const val STATUS_COMPLETED = "completed"

private fun statusRole(status: String): AppRole = when (status) {
    STATUS_COMPLETED -> AppRole.Success
    COMMAND_STATUS_RUNNING -> AppRole.Warning
    else -> AppRole.Error
}

@Composable
fun CommandDetailScreen(
    onBack: () -> Unit,
    viewModel: CommandDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.downloadedFile) {
        uiState.downloadedFile?.let { file ->
            context.startActivity(viewModel.shareIntent(file))
            viewModel.consumeDownloadedFile()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.command_detail_title),
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
            val error = uiState.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (uiState.loading) {
                CircularProgressIndicator()
                return@Column
            }

            val state = uiState.state ?: return@Column
            CommandSummary(state)

            if (uiState.hostedFiles.isNotEmpty() && state.status == STATUS_COMPLETED) {
                Text(
                    text = stringResource(R.string.command_detail_downloads_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                uiState.hostedFiles.forEach { hostedName ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    ) {
                        Text(hostedName, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
                        AppIconButton(
                            icon = Icons.Outlined.Download,
                            contentDescription = hostedName,
                            role = AppRole.Primary,
                            onClick = { viewModel.download(hostedName) },
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.command_detail_output_title),
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = state.output.ifEmpty { stringResource(R.string.command_detail_output_empty) },
                style = TextStyle(fontFamily = GeistMono, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = AppTheme.colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceVariant, RoundedCornerShape(AppRadius.card))
                    .padding(AppSpacing.md),
            )
        }
    }
}

@Composable
private fun CommandSummary(state: CommandState) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppStatusPill(text = state.status, role = statusRole(state.status))
            Text(state.agent, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
        }
        Text(state.command, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
        val exitCode = state.exitCode
        if (exitCode != null) {
            Text(
                text = stringResource(R.string.command_detail_exit_code, exitCode),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}
