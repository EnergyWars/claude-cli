package com.wafflehq.appgetter.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.appgetter.R
import com.wafflehq.appgetter.data.settings.ThemeMode
import com.wafflehq.appgetter.ui.components.AppBanner
import com.wafflehq.appgetter.ui.components.AppButton
import com.wafflehq.appgetter.ui.components.AppTextField
import com.wafflehq.appgetter.ui.components.SettingsDropdownField
import com.wafflehq.appgetter.ui.components.SettingsGroup
import com.wafflehq.appgetter.ui.components.SettingsScaffold
import com.wafflehq.appgetter.ui.navigation.hiltViewModel
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppSpacing
import com.wafflehq.appgetter.ui.theme.AppTheme

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    feedbackViewModel: FeedbackViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedbackState by feedbackViewModel.uiState.collectAsStateWithLifecycle()
    val modes = ThemeMode.entries
    val themeLabels = modes.map { themeModeLabel(it) }

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.lg),
        ) {
            SettingsGroup(label = stringResource(R.string.settings_group_general), tint = AppRole.Primary, fraction = 0.08f) {
                SettingsDropdownField(
                    label = stringResource(R.string.settings_design_label),
                    value = themeModeLabel(themeMode),
                    options = themeLabels,
                    selectedIndex = modes.indexOf(themeMode),
                    onSelect = { index -> viewModel.onThemeModeSelected(modes[index]) },
                )
            }

            Column(modifier = Modifier.padding(top = AppSpacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_group_connection),
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.sm),
                )
                AppTextField(
                    value = state.hostInput,
                    onValueChange = viewModel::onHostChange,
                    label = stringResource(R.string.settings_host_label),
                    role = AppRole.Neutral,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = state.portInput,
                    onValueChange = viewModel::onPortChange,
                    label = stringResource(R.string.settings_port_label),
                    role = AppRole.Neutral,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.sm),
                )
                Text(
                    text = stringResource(R.string.settings_connection_hint),
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppSpacing.sm, bottom = AppSpacing.md),
                )
                AppButton(
                    text = stringResource(R.string.settings_connection_save),
                    role = AppRole.Primary,
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(modifier = Modifier.padding(top = AppSpacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_group_feedback),
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.sm),
                )
                AppTextField(
                    value = feedbackState.text,
                    onValueChange = feedbackViewModel::onTextChange,
                    label = stringResource(R.string.feedback_text_label),
                    role = AppRole.Neutral,
                    enabled = !feedbackState.sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (feedbackState.sent) {
                    Text(
                        text = stringResource(R.string.feedback_sent),
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = AppSpacing.sm),
                    )
                }
                val feedbackError = feedbackState.error
                if (feedbackError != null) {
                    AppBanner(
                        title = stringResource(R.string.setup_error_title),
                        body = feedbackError,
                        role = AppRole.Error,
                        modifier = Modifier.padding(top = AppSpacing.sm),
                    )
                }
                AppButton(
                    text = stringResource(R.string.feedback_submit),
                    role = AppRole.Primary,
                    onClick = feedbackViewModel::send,
                    enabled = feedbackState.text.isNotBlank() && !feedbackState.sending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.sm),
                )
            }
        }
    }
}
