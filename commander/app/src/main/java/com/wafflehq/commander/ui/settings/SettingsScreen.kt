package com.wafflehq.commander.ui.settings

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsGroup
import com.wafflehq.commander.ui.components.SettingsGroupDivider
import com.wafflehq.commander.ui.components.SettingsListContent
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenContexts: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val connectionLabel by viewModel.connectionLabel.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsListContent(
                displayLabel = stringResource(R.string.settings_display_title),
                displaySubtitle = stringResource(R.string.settings_display_sub),
                onOpenDisplay = onOpenDisplay,
                contextsLabel = stringResource(R.string.settings_contexts_title),
                contextsSubtitle = stringResource(R.string.settings_contexts_sub),
                onOpenContexts = onOpenContexts,
            )
            SettingsGroupDivider()
            SettingsGroup(
                label = stringResource(R.string.settings_group_connection),
                tint = AppRole.Primary,
                fraction = 0.08f,
            ) {
                Text(
                    text = connectionLabel ?: stringResource(R.string.settings_connection_none),
                    color = AppTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppButton(
                    text = stringResource(R.string.settings_disconnect),
                    role = AppRole.Error,
                    variant = ButtonVariant.Outlined,
                    onClick = { viewModel.disconnect(onDisconnected) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
