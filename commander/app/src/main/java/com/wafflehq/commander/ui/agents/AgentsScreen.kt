package com.wafflehq.commander.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.SettingsListRow
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun AgentsScreen(
    onBack: () -> Unit,
    onOpenAgent: (agentCommand: String) -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.agents_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                val error = state.error
                if (error != null) {
                    AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
                }

                if (state.loading) {
                    CircularProgressIndicator()
                } else if (state.agents.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agents_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }
            state.agents.forEach { agent ->
                SettingsListRow(title = agent.command, subtitle = agent.description, onClick = { onOpenAgent(agent.command) })
            }
        }
    }
}
