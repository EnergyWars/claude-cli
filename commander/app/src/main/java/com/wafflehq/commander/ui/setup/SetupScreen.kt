package com.wafflehq.commander.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun SetupScreen(
    onConnected: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val manualSecret by viewModel.manualSecret.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val checking = status is SetupStatus.Checking

    LaunchedEffect(status) {
        if (status is SetupStatus.Connected) onConnected()
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

            when (status) {
                is SetupStatus.NeedsManualSecret -> {
                    AppBanner(
                        title = stringResource(R.string.setup_existing_title),
                        body = stringResource(R.string.setup_existing_body),
                        role = AppRole.Warning,
                    )
                    AppTextField(
                        value = manualSecret,
                        onValueChange = viewModel::onManualSecretChange,
                        label = stringResource(R.string.setup_secret_label),
                        role = AppRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    AppTextField(
                        value = host,
                        onValueChange = viewModel::onHostChange,
                        label = stringResource(R.string.setup_host_label),
                        role = AppRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppTextField(
                        value = port,
                        onValueChange = viewModel::onPortChange,
                        label = stringResource(R.string.setup_port_label),
                        role = AppRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val error = status as? SetupStatus.Error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error.message, role = AppRole.Error)
            }

            if (checking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.setup_connecting), color = AppTheme.colors.onSurfaceVariant)
                }
            }

            AppButton(
                text = stringResource(R.string.setup_connect),
                role = AppRole.Primary,
                onClick = if (status is SetupStatus.NeedsManualSecret) viewModel::connectWithManualSecret else viewModel::connect,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
