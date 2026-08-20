package com.wafflehq.commander.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.ManifestPath
import com.wafflehq.commander.data.db.CommandHistoryEntity
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.AppScaffold
import com.wafflehq.commander.ui.components.HeaderItem
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun HomeScreen(
    onOpenMenu: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgent: (agentCommand: String) -> Unit,
    onOpenPath: (pathName: String) -> Unit,
    onOpenCommand: (commandId: String, pathName: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val manifestState by viewModel.manifestState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    AppScaffold(
        activeItem = HeaderItem.Home,
        onOpenMenu = onOpenMenu,
        onNavigateHome = onNavigateHome,
        onOpenSettings = onOpenSettings,
    ) { padding ->
        when (val state = manifestState) {
            is ManifestState.Loading -> LoadingContent(padding)
            is ManifestState.Error -> ErrorContent(padding, state.message, onRetry = viewModel::refresh)
            is ManifestState.Loaded -> HomeContent(
                padding = padding,
                manifest = state.manifest,
                history = history,
                onRefresh = viewModel::refresh,
                onOpenAgent = onOpenAgent,
                onOpenPath = onOpenPath,
                onOpenCommand = onOpenCommand,
            )
        }
    }
}

@Composable
private fun LoadingContent(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(padding: PaddingValues, message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(padding).padding(AppSpacing.lg)) {
        AppBanner(
            title = stringResource(R.string.home_error_title),
            body = message,
            role = AppRole.Error,
            action = stringResource(R.string.home_retry) to onRetry,
        )
    }
}

@Composable
private fun HomeContent(
    padding: PaddingValues,
    manifest: Manifest,
    history: List<CommandHistoryEntity>,
    onRefresh: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenCommand: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .padding(padding),
        contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_agents_title), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.onSurface)
                AppIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.home_retry),
                    role = AppRole.Neutral,
                    onClick = onRefresh,
                )
            }
        }
        items(manifest.agents, key = { "agent:${it.command}" }) { agent ->
            SimpleRow(title = agent.command, subtitle = agent.description, onClick = { onOpenAgent(agent.command) })
        }

        item {
            Text(
                stringResource(R.string.home_paths_title),
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onSurface,
                modifier = Modifier.padding(top = AppSpacing.lg),
            )
        }
        if (manifest.paths.isEmpty()) {
            item { EmptyRow(stringResource(R.string.home_paths_empty)) }
        }
        items(manifest.paths, key = { "path:${it.name}" }) { path ->
            SimpleRow(
                title = path.name,
                subtitle = pathSubtitle(path),
                onClick = { onOpenPath(path.name) },
            )
        }

        if (history.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTheme.colors.onSurface,
                    modifier = Modifier.padding(top = AppSpacing.lg),
                )
            }
            items(history, key = { it.id }) { entry ->
                SimpleRow(title = entry.label, subtitle = entry.pathName, onClick = { onOpenCommand(entry.id, entry.pathName) })
            }
        }
    }
}

private fun pathSubtitle(path: ManifestPath): String {
    val commandCount = path.commands.size
    val hostedCount = path.hosted.size
    return "$commandCount Commands · $hostedCount Dateien"
}

@Composable
private fun SimpleRow(title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
}
