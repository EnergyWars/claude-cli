package com.wafflehq.commander.ui.allsessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.ActiveSession
import com.wafflehq.commander.data.api.SessionActivity
import com.wafflehq.commander.data.api.isBackground
import com.wafflehq.commander.data.api.sessionActivity
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppStatusPill
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import java.time.Instant

@Composable
fun AllSessionsScreen(
    onBack: () -> Unit,
    viewModel: AllSessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingKill by remember { mutableStateOf<ActiveSession?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollSessions()
        }
    }

    val killTarget = pendingKill
    if (killTarget != null) {
        AppConfirmDialog(
            title = stringResource(R.string.all_sessions_kill_confirm_title),
            body = stringResource(R.string.all_sessions_kill_confirm_body, killTarget.name),
            confirmText = stringResource(R.string.all_sessions_kill_confirm_confirm),
            dismissText = stringResource(R.string.label_cancel),
            onConfirm = {
                viewModel.kill(killTarget)
                pendingKill = null
            },
            onDismiss = { pendingKill = null },
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.all_sessions_title),
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
            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.all_sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.all_sessions_summary,
                        state.sessions.size,
                        state.workingCount,
                        state.waitingCount,
                        state.idleCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = AppSpacing.bottomSafeArea),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                items(state.sessions, key = { it.sessionId }) { session ->
                    SessionRow(
                        session = session,
                        killing = state.killingSessionId == session.sessionId,
                        killEnabled = state.killingSessionId == null,
                        onKill = { pendingKill = session },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ActiveSession,
    killing: Boolean,
    killEnabled: Boolean,
    onKill: () -> Unit,
) {
    val activity = session.sessionActivity()
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
                    modifier = Modifier.weight(1f),
                )
                AppStatusPill(
                    text = stringResource(activityLabelRes(activity)),
                    role = activityRole(activity),
                )
            }
            Text(
                text = session.cwd,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
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
                text = stringResource(
                    R.string.remote_sessions_started_at,
                    formatTimestamp(Instant.ofEpochMilli(session.startedAt).toString()),
                ),
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
            AppButton(
                text = stringResource(R.string.all_sessions_kill_button),
                role = AppRole.Error,
                variant = ButtonVariant.Tonal,
                onClick = onKill,
                enabled = killEnabled && !killing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun activityRole(activity: SessionActivity): AppRole = when (activity) {
    SessionActivity.Working -> AppRole.Primary
    SessionActivity.Waiting -> AppRole.Warning
    SessionActivity.Idle -> AppRole.Success
}

internal fun activityLabelRes(activity: SessionActivity): Int = when (activity) {
    SessionActivity.Working -> R.string.all_sessions_activity_working
    SessionActivity.Waiting -> R.string.all_sessions_activity_waiting
    SessionActivity.Idle -> R.string.all_sessions_activity_idle
}
