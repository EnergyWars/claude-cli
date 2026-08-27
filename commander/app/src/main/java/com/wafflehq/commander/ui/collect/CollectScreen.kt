package com.wafflehq.commander.ui.collect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.CollectErrorEntry
import com.wafflehq.commander.data.api.CollectResultEntry
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing

@Composable
fun CollectScreen(
    onBack: () -> Unit,
    viewModel: CollectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.collect_title),
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
            AppButton(
                text = stringResource(R.string.collect_button),
                role = AppRole.Primary,
                onClick = viewModel::collect,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.loading) {
                CircularProgressIndicator()
            }

            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            state.summary?.results?.forEach { result -> CollectResultRow(result) }
            state.summary?.errors?.forEach { error -> CollectErrorRow(error) }
        }
    }
}

@Composable
private fun CollectResultRow(result: CollectResultEntry) {
    AppBanner(
        title = result.targetName,
        body = stringResource(R.string.collect_result_ok, result.fileName),
        role = AppRole.Success,
    )
}

@Composable
private fun CollectErrorRow(error: CollectErrorEntry) {
    AppBanner(title = error.targetName, body = error.error, role = AppRole.Error)
}
