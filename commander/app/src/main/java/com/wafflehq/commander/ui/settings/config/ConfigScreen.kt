package com.wafflehq.commander.ui.settings.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing

@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    onOpenVersions: () -> Unit,
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_config_title),
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

            AppButton(
                text = stringResource(R.string.settings_config_versions),
                role = AppRole.Secondary,
                variant = ButtonVariant.Outlined,
                onClick = onOpenVersions,
                modifier = Modifier.fillMaxWidth(),
            )

            AppTextField(
                value = state.jsonInput,
                onValueChange = viewModel::onJsonChange,
                label = stringResource(R.string.settings_config_json_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
            )

            val warning = state.warning
            if (warning != null) {
                AppBanner(title = stringResource(R.string.settings_config_warning_title), body = warning, role = AppRole.Warning)
            }

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.saved && error == null) {
                AppBanner(
                    title = stringResource(R.string.settings_config_saved_title),
                    body = stringResource(R.string.settings_config_saved_body),
                    role = AppRole.Success,
                )
            }

            AppButton(
                text = stringResource(R.string.settings_config_save),
                role = AppRole.Primary,
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
