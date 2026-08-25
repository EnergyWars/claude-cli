package com.wafflehq.commander.ui.commands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.PathCommandEntry
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun CommandsScreen(
    onBack: () -> Unit,
    onCommandStarted: (commandId: String, pathName: String) -> Unit,
    viewModel: CommandsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.startedCommandId) {
        state.startedCommandId?.let { id ->
            onCommandStarted(id, viewModel.pathName)
            viewModel.consumeStartedCommand()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.commands_title),
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
            } else if (state.commands.isEmpty()) {
                Text(
                    text = stringResource(R.string.commands_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            state.commands.forEach { command ->
                CommandRow(command = command, onClick = { viewModel.runCommand(command.key) })
            }
        }
    }
}

@Composable
private fun CommandRow(command: PathCommandEntry, onClick: () -> Unit) {
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
