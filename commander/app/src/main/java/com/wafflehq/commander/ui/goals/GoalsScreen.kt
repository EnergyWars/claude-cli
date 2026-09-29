package com.wafflehq.commander.ui.goals

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.GoalEntry
import com.wafflehq.commander.data.api.GoalListGroup
import com.wafflehq.commander.data.api.doneCount
import com.wafflehq.commander.data.api.isReady
import com.wafflehq.commander.data.api.overallCount
import com.wafflehq.commander.data.api.runningCount
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppChip
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.AppStatusPill
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.CardVariant
import com.wafflehq.commander.ui.components.ChipVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import com.wafflehq.commander.ui.theme.GeistMono
import kotlinx.coroutines.launch

@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    onGoalStarted: (commandId: String) -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.goals_copied)
    val sessionStartedMessage = stringResource(R.string.goals_session_started)

    LaunchedEffect(state.startedCommandId) {
        val commandId = state.startedCommandId ?: return@LaunchedEffect
        onGoalStarted(commandId)
        viewModel.consumeStartedCommand()
    }

    LaunchedEffect(state.startedSessionId) {
        if (state.startedSessionId == null) return@LaunchedEffect
        Toast.makeText(context, sessionStartedMessage, Toast.LENGTH_SHORT).show()
        viewModel.consumeStartedSession()
    }

    fun copyContent(goal: GoalEntry) {
        coroutineScope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(goal.fileName, goal.command)))
        }
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    SettingsScaffold(
        title = stringResource(R.string.goals_title),
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
            } else if (error == null && state.goalGroups.all { it.goals.isEmpty() }) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.goals_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = AppSpacing.bottomSafeArea),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                for (group in state.goalGroups) {
                    if (group.goals.isEmpty()) continue

                    val expanded = group.folder in state.expandedFolders
                    item(key = "header-${group.folder}") {
                        GoalGroupHeader(
                            group = group,
                            expanded = expanded,
                            onToggle = { viewModel.toggleFolder(group.folder) },
                        )
                    }
                    if (expanded) {
                        items(group.goals, key = { "${group.folder}/${it.fileName}" }) { goal ->
                            val key = goalKey(group.folder, goal.fileName)
                            GoalCard(
                                goal = goal,
                                starting = state.startingKey == key,
                                onStart = { viewModel.startGoal(group.folder, goal.fileName) },
                                onStartInteractive = {
                                    viewModel.startGoal(group.folder, goal.fileName, interactive = true)
                                },
                                onCopy = { copyContent(goal) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalGroupHeader(group: GoalListGroup, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(
                text = group.planTitle ?: group.folder,
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.goals_progress,
                    group.doneCount(),
                    group.overallCount(),
                    group.runningCount(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(if (expanded) R.string.goals_collapse else R.string.goals_expand),
            tint = AppTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun GoalCard(
    goal: GoalEntry,
    starting: Boolean,
    onStart: () -> Unit,
    onStartInteractive: () -> Unit,
    onCopy: () -> Unit,
) {
    AppCard(role = AppRole.Neutral, variant = CardVariant.Outlined, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(
                        text = "${goal.id} – ${goal.title}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.onSurface,
                    )
                    Text(
                        text = goal.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                AppStatusPill(
                    text = stringResource(
                        when {
                            goal.running -> R.string.goals_status_running
                            goal.isReady() -> R.string.goals_status_ready
                            else -> R.string.goals_status_blocked
                        },
                    ),
                    role = when {
                        goal.running -> AppRole.Primary
                        goal.isReady() -> AppRole.Success
                        else -> AppRole.Warning
                    },
                )
            }

            if (goal.description.isNotBlank()) {
                Text(
                    text = goal.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurface,
                )
            }

            if (goal.dependsOn.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    for (dependency in goal.dependsOn) {
                        val satisfied = dependency !in goal.missingDependencies
                        AppChip(
                            label = dependency,
                            role = if (satisfied) AppRole.Success else AppRole.Error,
                            variant = ChipVariant.Assist,
                        )
                    }
                }
            }

            Text(
                text = goal.command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                color = AppTheme.colors.onSurface,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceVariant, RoundedCornerShape(AppRadius.card))
                    .padding(AppSpacing.md),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val startable = goal.isReady() && !goal.running && !starting
                AppButton(
                    text = stringResource(R.string.goals_start),
                    role = AppRole.Primary,
                    variant = ButtonVariant.Tonal,
                    enabled = startable,
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = stringResource(R.string.goals_start_interactive),
                    role = AppRole.Primary,
                    variant = ButtonVariant.Outlined,
                    enabled = startable,
                    onClick = onStartInteractive,
                    modifier = Modifier.weight(1f),
                )
                AppIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.goals_copy),
                    role = AppRole.Neutral,
                    enabled = !goal.running,
                    onClick = onCopy,
                )
            }
        }
    }
}
