package com.wafflehq.commander.ui.login

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onChangeConnection: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val connectionLabel by viewModel.connectionLabel.collectAsStateWithLifecycle()
    val code by viewModel.code.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val checking = status is LoginStatus.Checking

    LaunchedEffect(status) {
        if (status is LoginStatus.LoggedIn) onLoggedIn()
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
            if (connectionLabel != null) {
                Text(
                    text = connectionLabel.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
            Text(
                text = connectionLabel
                    ?.let { stringResource(R.string.login_lede, "http://$it/auth/setup") }
                    .orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )

            AppTextField(
                value = code,
                onValueChange = viewModel::onCodeChange,
                label = stringResource(R.string.login_code_label),
                role = AppRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            )

            val error = status as? LoginStatus.Error
            if (error != null) {
                AppBanner(title = stringResource(R.string.login_error_title), body = error.message, role = AppRole.Error)
            }

            if (checking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.login_checking),
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }

            AppButton(
                text = stringResource(R.string.login_submit),
                role = AppRole.Primary,
                onClick = viewModel::submit,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            )

            AppButton(
                text = stringResource(R.string.login_change_connection),
                role = AppRole.Secondary,
                variant = ButtonVariant.Text,
                onClick = {
                    viewModel.changeConnection()
                    onChangeConnection()
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
