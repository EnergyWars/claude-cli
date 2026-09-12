package com.wafflehq.commander.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppChip
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.ChipVariant
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun SetupScreen(
    onConnectionSaved: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val host by viewModel.host.collectAsStateWithLifecycle()
    val hostHistory by viewModel.hostHistory.collectAsStateWithLifecycle()
    val hostInputVisible by viewModel.hostInputVisible.collectAsStateWithLifecycle()
    val portOption by viewModel.portOption.collectAsStateWithLifecycle()
    val customPort by viewModel.customPort.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val checking = status is SetupStatus.Checking
    val discovering = status is SetupStatus.Discovering
    val busy = checking || discovering

    LaunchedEffect(status) {
        if (status is SetupStatus.Connected) onConnectionSaved()
    }

    Surface(color = AppTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.onSurface,
            )
            Text(
                text = stringResource(R.string.setup_lede),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )

            if (hostHistory.isNotEmpty()) {
                SectionLabel(stringResource(R.string.setup_host_known))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    hostHistory.forEach { known ->
                        AppChip(
                            label = known,
                            role = AppRole.Primary,
                            variant = ChipVariant.Filter,
                            selected = !hostInputVisible && known == host,
                            onClick = { viewModel.onHostSelected(known) },
                        )
                    }
                    AppChip(
                        label = stringResource(R.string.setup_host_new),
                        role = AppRole.Secondary,
                        variant = ChipVariant.Filter,
                        selected = hostInputVisible,
                        onClick = viewModel::onNewHostSelected,
                    )
                }
            }

            if (hostInputVisible) {
                AppTextField(
                    value = host,
                    onValueChange = viewModel::onHostChange,
                    label = stringResource(R.string.setup_host_label),
                    role = AppRole.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionLabel(stringResource(R.string.setup_port_label))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                PortOption.entries.forEach { option ->
                    AppChip(
                        label = stringResource(option.labelRes),
                        role = AppRole.Primary,
                        variant = ChipVariant.Filter,
                        selected = option == portOption,
                        onClick = { viewModel.onPortOptionChange(option) },
                    )
                }
            }
            if (portOption == PortOption.Custom) {
                AppTextField(
                    value = customPort,
                    onValueChange = viewModel::onCustomPortChange,
                    label = stringResource(R.string.setup_port_custom_label),
                    role = AppRole.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AppButton(
                text = stringResource(R.string.setup_discover),
                role = AppRole.Secondary,
                variant = ButtonVariant.Outlined,
                onClick = viewModel::discover,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )

            val error = status as? SetupStatus.Error
            if (error != null) {
                AppBanner(
                    title = stringResource(R.string.setup_error_title),
                    body = when (val message = error.message) {
                        is SetupErrorMessage.Text -> message.value
                        is SetupErrorMessage.Resource -> stringResource(message.id)
                    },
                    role = AppRole.Error,
                )
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(if (discovering) R.string.setup_discovering else R.string.setup_connecting),
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }

            AppButton(
                text = stringResource(R.string.setup_connect),
                role = AppRole.Primary,
                onClick = viewModel::connect,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = AppTheme.colors.onSurfaceVariant,
    )
}
