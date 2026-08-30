package com.wafflehq.commander.data.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.downloadHistoryDataStore by preferencesDataStore(name = "download_history")

private const val PENDING_PATH_PREFIX = "pending_path::"

data class PendingInstall(val key: String, val filePath: String)

fun parsePendingInstalls(raw: Map<String, String>): List<PendingInstall> =
    raw.filterKeys { it.startsWith(PENDING_PATH_PREFIX) }
        .map { (key, value) -> PendingInstall(key.removePrefix(PENDING_PATH_PREFIX), value) }

@Singleton
class DownloadHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val pendingInstalls: Flow<List<PendingInstall>> = context.downloadHistoryDataStore.data.map { prefs ->
        parsePendingInstalls(prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() })
    }

    suspend fun recordPendingInstall(key: String, filePath: String) {
        context.downloadHistoryDataStore.edit { prefs -> prefs[stringPreferencesKey(PENDING_PATH_PREFIX + key)] = filePath }
    }

    suspend fun clearPendingInstall(key: String) {
        context.downloadHistoryDataStore.edit { prefs -> prefs.remove(stringPreferencesKey(PENDING_PATH_PREFIX + key)) }
    }
}
