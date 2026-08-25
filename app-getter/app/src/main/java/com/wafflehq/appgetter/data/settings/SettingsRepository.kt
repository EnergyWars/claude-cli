package com.wafflehq.appgetter.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DEFAULT_SERVER_PORT = 8787

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class ServerOverride(val host: String?, val port: Int)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val hostKey = stringPreferencesKey("server_host")
    private val portKey = intPreferencesKey("server_port")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.fromName(prefs[themeModeKey])
    }

    val serverOverride: Flow<ServerOverride> = context.settingsDataStore.data.map { prefs ->
        ServerOverride(prefs[hostKey]?.ifBlank { null }, prefs[portKey] ?: DEFAULT_SERVER_PORT)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs -> prefs[themeModeKey] = mode.name }
    }

    suspend fun setServerOverride(host: String?, port: Int) {
        context.settingsDataStore.edit { prefs ->
            if (host.isNullOrBlank()) prefs.remove(hostKey) else prefs[hostKey] = host
            prefs[portKey] = port
        }
    }
}
