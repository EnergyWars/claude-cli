package com.wafflehq.commander.ui.remotesessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.wafflehq.commander.data.api.RemoteAgentSession
import com.wafflehq.commander.data.api.isBackground
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppStatusPill
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import java.time.Instant

@Composable
fun RemoteSessionsScreen(
    onBack: () -> Unit,
    viewModel: RemoteSessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.remote_sessions_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            AppTextField(
                value = state.nameInput,
                onValueChange = viewModel::onNameInputChange,
                label = stringResource(R.string.remote_sessions_name_label),
                role = AppRole.Primary,
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                text = stringResource(R.string.remote_sessions_start_button),
                role = AppRole.Primary,
                onClick = viewModel::startSession,
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth(),
            )

            val lastStartedId = state.lastStartedId
            if (lastStartedId != null) {
                AppBanner(
                    title = stringResource(R.string.remote_sessions_started_title),
                    body = stringResource(R.string.remote_sessions_started_body, lastStartedId),
                    role = AppRole.Success,
                )
            }

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.remote_sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                items(state.sessions, key = { it.sessionId }) { session -> RemoteSessionRow(session) }
            }
        }
    }
}

@Composable
private fun RemoteSessionRow(session: RemoteAgentSession) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onSurface,
                )
                val status = session.status
                if (status != null) {
                    AppStatusPill(text = status, role = remoteSessionStatusRole(status))
                }
            }
            Text(
                text = if (session.isBackground()) {
                    stringResource(R.string.remote_sessions_kind_background, session.id.orEmpty())
                } else {
                    stringResource(R.string.remote_sessions_kind_interactive)
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.remote_sessions_started_at, formatEpochMillis(session.startedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            val waitingFor = session.waitingFor
            if (waitingFor != null) {
                Text(
                    text = stringResource(R.string.remote_sessions_waiting_for, waitingFor),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatEpochMillis(millis: Long): String = formatTimestamp(Instant.ofEpochMilli(millis).toString())

private fun remoteSessionStatusRole(status: String): AppRole = when (status) {
    "waiting" -> AppRole.Warning
    else -> AppRole.Neutral
}
