package com.wafflehq.commander.ui.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppChip
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ChipVariant
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun RunAgentScreen(
    onBack: () -> Unit,
    onStarted: (commandId: String) -> Unit,
    viewModel: RunAgentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdCommandId) {
        state.createdCommandId?.let(onStarted)
    }

    SettingsScaffold(
        title = state.agentCommand,
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
            if (state.loading) {
                CircularProgressIndicator()
                return@Column
            }

            val modelStandardLabel = stringResource(R.string.run_agent_model_default)

            if (state.agentDescription.isNotEmpty()) {
                Text(state.agentDescription, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.agent_run_path_label, state.pathName),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )

            if (state.contexts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = stringResource(R.string.agent_run_context_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        state.contexts.forEach { context ->
                            AppChip(
                                label = context.name,
                                role = AppRole.Primary,
                                variant = ChipVariant.Filter,
                                selected = context.id in state.selectedContextIds,
                                onClick = { viewModel.onContextToggled(context.id) },
                            )
                        }
                    }
                }
            }
            SettingsDropdownField(
                label = stringResource(R.string.run_agent_model_label),
                value = RUN_AGENT_MODELS.getOrNull(state.selectedModelIndex)?.ifEmpty { modelStandardLabel } ?: modelStandardLabel,
                options = RUN_AGENT_MODELS.map { it.ifEmpty { modelStandardLabel } },
                selectedIndex = state.selectedModelIndex,
                onSelect = viewModel::onModelSelected,
            )
            AppTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChange,
                label = stringResource(R.string.run_agent_prompt_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            AppButton(
                text = stringResource(R.string.run_agent_start),
                role = AppRole.Primary,
                onClick = viewModel::start,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
