package com.wafflehq.commander.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wafflehq.commander.R
import com.wafflehq.commander.data.settings.ThemeMode
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsGroup
import com.wafflehq.commander.ui.theme.AppRole

@Composable
fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@Composable
fun DisplaySettingsContent(
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ThemeMode.entries
    val themeLabels = modes.map { themeModeLabel(it) }

    Column(modifier = modifier) {
        SettingsGroup(
            label = stringResource(R.string.settings_group_general),
            tint = AppRole.Primary,
            fraction = 0.08f,
        ) {
            SettingsDropdownField(
                label = stringResource(R.string.settings_design_label),
                value = themeModeLabel(themeMode),
                options = themeLabels,
                selectedIndex = modes.indexOf(themeMode),
                onSelect = { index -> onThemeSelected(modes[index]) },
            )
        }
    }
}
