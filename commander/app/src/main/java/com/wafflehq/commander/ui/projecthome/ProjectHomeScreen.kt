package com.wafflehq.commander.ui.projecthome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.CLAUDE_MODELS
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.CardVariant
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsListRow
import com.wafflehq.commander.ui.components.UsageLimitBanner
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun ProjectHomeScreen(
    onOpenCommands: (pathName: String) -> Unit,
    onOpenDownloads: (pathName: String) -> Unit,
    onOpenAgents: (pathName: String) -> Unit,
    onOpenHistory: (pathName: String) -> Unit,
    onOpenGoals: (pathName: String) -> Unit,
    onOpenSchedulers: (pathName: String) -> Unit,
    onCommandStarted: (commandId: String, pathName: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProjectHomeViewModel = hiltViewModel(),
) {
    val selectedProject by viewModel.selectedProjectName.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val usageBannerExpanded by viewModel.usageBannerExpanded.collectAsStateWithLifecycle()
    val projectName = selectedProject
    var confirmStartAll by remember { mutableStateOf(false) }
    var goalCommandDialog by remember { mutableStateOf<GoalCommand?>(null) }
    var goalCommandText by remember { mutableStateOf("") }

    LaunchedEffect(state.startedCommandId) {
        val commandId = state.startedCommandId ?: return@LaunchedEffect
        viewModel.onStartedCommandConsumed()
        projectName?.let { onCommandStarted(commandId, it) }
    }

    if (projectName == null) {
        Surface(color = AppTheme.colors.background, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    Surface(color = AppTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.lg,
                    top = AppSpacing.lg,
                    end = AppSpacing.lg,
                    bottom = AppSpacing.lg + AppSpacing.bottomSafeArea,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            UsageLimitBanner(
                limits = state.usageLimits,
                expanded = usageBannerExpanded,
                onExpandedChange = viewModel::onUsageBannerExpandedChanged,
                lastUpdatedAt = state.usageLastUpdatedAt,
                refreshing = state.usageRefreshing,
                onRefresh = viewModel::refreshUsage,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                ProjectDropdown(
                    modifier = Modifier.weight(1f),
                    projectName = projectName,
                    availablePaths = state.availablePaths,
                    onProjectSelected = viewModel::onProjectSelected,
                )
                AppIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.label_settings),
                    role = AppRole.Neutral,
                    onClick = onOpenSettings,
                )
            }

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            val remoteSessionStartedId = state.remoteSessionStartedId
            if (remoteSessionStartedId != null) {
                AppBanner(
                    title = stringResource(R.string.project_home_remote_session_started_title),
                    body = stringResource(R.string.project_home_remote_session_started_body, remoteSessionStartedId),
                    role = AppRole.Success,
                )
            }

            ProjectHomeHighlightCard(
                icon = Icons.Outlined.Cast,
                title = stringResource(R.string.project_home_remote_session_start),
                subtitle = stringResource(R.string.project_home_remote_session_start_subtitle),
                enabled = !state.startingRemoteSession,
                onClick = viewModel::startRemoteSession,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                AppButton(
                    text = stringResource(R.string.project_home_goal_plan),
                    role = AppRole.Primary,
                    onClick = {
                        goalCommandText = ""
                        goalCommandDialog = GoalCommand.Plan
                    },
                    enabled = !state.startingGoalCommand,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = stringResource(R.string.project_home_goal_prompt),
                    role = AppRole.Primary,
                    onClick = {
                        goalCommandText = ""
                        goalCommandDialog = GoalCommand.Prompt
                    },
                    enabled = !state.startingGoalCommand,
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.startableGoalCount > 0) {
                ProjectHomeHighlightCard(
                    icon = Icons.Outlined.PlayArrow,
                    title = stringResource(R.string.project_home_start_all_goals),
                    subtitle = pluralStringResource(
                        R.plurals.project_home_start_all_goals_subtitle,
                        state.startableGoalCount,
                        state.startableGoalCount,
                    ),
                    enabled = !state.startingAllGoals,
                    onClick = { confirmStartAll = true },
                )
            }

            ProjectHomeHighlightCard(
                icon = Icons.Outlined.SmartToy,
                title = stringResource(R.string.project_home_agents),
                subtitle = stringResource(R.string.project_home_agents_subtitle),
                onClick = { onOpenAgents(projectName) },
            )

            SettingsListRow(
                title = stringResource(R.string.project_home_commands),
                subtitle = null,
                onClick = { onOpenCommands(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_downloads),
                subtitle = null,
                onClick = { onOpenDownloads(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_history),
                subtitle = null,
                onClick = { onOpenHistory(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_goals),
                subtitle = null,
                onClick = { onOpenGoals(projectName) },
            )
            if (state.hasSchedulers) {
                SettingsListRow(
                    title = stringResource(R.string.project_home_schedulers),
                    subtitle = null,
                    onClick = { onOpenSchedulers(projectName) },
                )
            }
        }
    }

    val activeGoalCommand = goalCommandDialog
    if (activeGoalCommand != null) {
        AppConfirmDialog(
            title = activeGoalCommand.slashCommand,
            body = stringResource(R.string.project_home_goal_command_body, activeGoalCommand.slashCommand),
            confirmText = stringResource(R.string.project_home_goal_command_run),
            dismissText = stringResource(R.string.project_home_start_all_goals_cancel),
            confirmRole = AppRole.Primary,
            onConfirm = {
                goalCommandDialog = null
                viewModel.runGoalCommand(activeGoalCommand, goalCommandText)
            },
            onDismiss = { goalCommandDialog = null },
        ) {
            AppTextField(
                value = goalCommandText,
                onValueChange = { goalCommandText = it },
                label = stringResource(R.string.project_home_goal_command_text_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmStartAll) {
        val effectiveStartCount = state.startAllMaxCount?.let { minOf(it, state.startableGoalCount) } ?: state.startableGoalCount
        AppConfirmDialog(
            title = stringResource(R.string.project_home_start_all_goals_confirm_title),
            body = pluralStringResource(
                R.plurals.project_home_start_all_goals_confirm_body,
                effectiveStartCount,
                effectiveStartCount,
            ),
            confirmText = stringResource(R.string.project_home_start_all_goals_confirm),
            dismissText = stringResource(R.string.project_home_start_all_goals_cancel),
            confirmRole = AppRole.Primary,
            onConfirm = {
                confirmStartAll = false
                viewModel.startAllGoals()
            },
            onDismiss = { confirmStartAll = false },
        ) {
            SettingsDropdownField(
                label = stringResource(R.string.project_home_start_all_goals_model_label),
                value = state.startAllModel,
                options = CLAUDE_MODELS,
                selectedIndex = CLAUDE_MODELS.indexOf(state.startAllModel),
                onSelect = { index -> viewModel.onStartAllModelSelected(CLAUDE_MODELS[index]) },
            )
            AppTextField(
                value = state.startAllMaxCount?.toString().orEmpty(),
                onValueChange = viewModel::onStartAllMaxCountChanged,
                label = stringResource(R.string.project_home_start_all_goals_max_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
private fun ProjectHomeHighlightCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.card))
            .clickable(enabled = enabled, onClick = onClick),
        role = AppRole.Primary,
        variant = CardVariant.Filled,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(AppRadius.card))
                    .background(colors.primary.onContainer.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary.onContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary.onContainer,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary.onContainer.copy(alpha = 0.75f),
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = colors.primary.onContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ProjectDropdown(
    modifier: Modifier = Modifier,
    projectName: String,
    availablePaths: List<String>,
    onProjectSelected: (String) -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        val pillShape = RoundedCornerShape(AppRadius.pill)
        Row(
            modifier = Modifier
                .clip(pillShape)
                .border(1.dp, colors.outline, pillShape)
                .background(colors.surface)
                .clickable { expanded = true }
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(
                text = projectName,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Icon(imageVector = Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = colors.surface) {
            availablePaths.forEach { path ->
                DropdownMenuItem(
                    text = { Text(path) },
                    onClick = {
                        expanded = false
                        onProjectSelected(path)
                    },
                )
            }
        }
    }
}
