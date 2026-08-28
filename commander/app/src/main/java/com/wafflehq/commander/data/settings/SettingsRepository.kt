package com.wafflehq.commander.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val selectedProjectKey = stringPreferencesKey("selected_project")
    private val usageBannerExpandedKey = booleanPreferencesKey("usage_banner_expanded")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.fromName(prefs[themeModeKey])
    }

    val selectedProjectName: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[selectedProjectKey]
    }

    val usageBannerExpanded: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[usageBannerExpandedKey] ?: true
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[themeModeKey] = mode.name
        }
    }

    suspend fun setSelectedProject(name: String?) {
        context.settingsDataStore.edit { prefs ->
            if (name != null) prefs[selectedProjectKey] = name else prefs.remove(selectedProjectKey)
        }
    }

    suspend fun setUsageBannerExpanded(expanded: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[usageBannerExpandedKey] = expanded
        }
    }
}
