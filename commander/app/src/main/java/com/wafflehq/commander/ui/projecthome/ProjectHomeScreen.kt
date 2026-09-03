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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.CardVariant
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
    onOpenTickets: (pathName: String) -> Unit,
    onOpenHistory: (pathName: String) -> Unit,
    onOpenFeedback: (pathName: String) -> Unit,
    onOpenCollect: (pathName: String) -> Unit,
    onOpenStats: (pathName: String) -> Unit,
    onOpenRemoteSessions: (pathName: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProjectHomeViewModel = hiltViewModel(),
) {
    val selectedProject by viewModel.selectedProjectName.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val usageBannerExpanded by viewModel.usageBannerExpanded.collectAsStateWithLifecycle()
    val projectName = selectedProject

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
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            UsageLimitBanner(
                limits = state.usageLimits,
                expanded = usageBannerExpanded,
                onExpandedChange = viewModel::onUsageBannerExpandedChanged,
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

            ProjectHomeDevelopmentCard(onClick = { onOpenAgents(projectName) })

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
                title = stringResource(R.string.project_home_tickets),
                subtitle = null,
                onClick = { onOpenTickets(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_history),
                subtitle = null,
                onClick = { onOpenHistory(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_feedback),
                subtitle = null,
                onClick = { onOpenFeedback(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_collect),
                subtitle = null,
                onClick = { onOpenCollect(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_stats),
                subtitle = null,
                onClick = { onOpenStats(projectName) },
            )
            SettingsListRow(
                title = stringResource(R.string.project_home_remote_sessions),
                subtitle = null,
                onClick = { onOpenRemoteSessions(projectName) },
            )
        }
    }
}

@Composable
private fun ProjectHomeDevelopmentCard(onClick: () -> Unit) {
    val colors = AppTheme.colors
    AppCard(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.card)).clickable(onClick = onClick),
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
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = colors.primary.onContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.project_home_agents),
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
                    color = colors.primary.onContainer,
                )
                Text(
                    text = stringResource(R.string.project_home_agents_subtitle),
                    style = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
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
