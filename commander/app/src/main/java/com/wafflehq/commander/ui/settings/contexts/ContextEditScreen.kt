package com.wafflehq.commander.ui.settings.contexts

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
import androidx.compose.runtime.LaunchedEffect
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
fun ContextEditScreen(
    onBack: () -> Unit,
    viewModel: ContextEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onBack()
    }

    SettingsScaffold(
        title = stringResource(if (viewModel.isNew) R.string.context_edit_new_title else R.string.context_edit_edit_title),
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

            AppTextField(
                value = state.nameInput,
                onValueChange = viewModel::onNameChange,
                label = stringResource(R.string.context_edit_name_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = state.valueInput,
                onValueChange = viewModel::onValueChange,
                label = stringResource(R.string.context_edit_value_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            AppButton(
                text = stringResource(R.string.context_edit_save),
                role = AppRole.Primary,
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!viewModel.isNew) {
                AppButton(
                    text = stringResource(R.string.context_edit_delete),
                    role = AppRole.Error,
                    variant = ButtonVariant.Outlined,
                    onClick = viewModel::delete,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
