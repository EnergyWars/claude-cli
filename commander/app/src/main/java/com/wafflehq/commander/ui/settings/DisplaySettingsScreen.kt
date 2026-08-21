package com.wafflehq.commander.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel

@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_display_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        DisplaySettingsContent(
            themeMode = themeMode,
            onThemeSelected = viewModel::onThemeModeSelected,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        )
    }
}
