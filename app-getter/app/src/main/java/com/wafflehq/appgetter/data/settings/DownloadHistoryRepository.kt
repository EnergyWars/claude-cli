package com.wafflehq.appgetter.data.settings

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

private const val PENDING_TIMESTAMP_PREFIX = "pending_ts::"
private const val PENDING_PATH_PREFIX = "pending_path::"

data class PendingInstall(val fileName: String, val timestamp: String, val filePath: String)

fun parsePendingInstalls(raw: Map<String, String>): List<PendingInstall> {
    val timestamps = raw.filterKeys { it.startsWith(PENDING_TIMESTAMP_PREFIX) }
        .mapKeys { (key, _) -> key.removePrefix(PENDING_TIMESTAMP_PREFIX) }
    val paths = raw.filterKeys { it.startsWith(PENDING_PATH_PREFIX) }
        .mapKeys { (key, _) -> key.removePrefix(PENDING_PATH_PREFIX) }
    return timestamps.mapNotNull { (fileName, timestamp) ->
        val path = paths[fileName] ?: return@mapNotNull null
        PendingInstall(fileName, timestamp, path)
    }
}

@Singleton
class DownloadHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val downloadedTimestamps: Flow<Map<String, String>> = context.downloadHistoryDataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { (key, _) -> !key.name.startsWith(PENDING_TIMESTAMP_PREFIX) && !key.name.startsWith(PENDING_PATH_PREFIX) }
            .associate { (key, value) -> key.name to value.toString() }
    }

    val pendingInstalls: Flow<List<PendingInstall>> = context.downloadHistoryDataStore.data.map { prefs ->
        parsePendingInstalls(prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() })
    }

    suspend fun recordDownload(fileName: String, timestamp: String) {
        context.downloadHistoryDataStore.edit { prefs -> prefs[stringPreferencesKey(fileName)] = timestamp }
    }

    suspend fun recordPendingInstall(fileName: String, timestamp: String, filePath: String) {
        context.downloadHistoryDataStore.edit { prefs ->
            prefs[stringPreferencesKey(PENDING_TIMESTAMP_PREFIX + fileName)] = timestamp
            prefs[stringPreferencesKey(PENDING_PATH_PREFIX + fileName)] = filePath
        }
    }

    suspend fun clearPendingInstall(fileName: String) {
        context.downloadHistoryDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(PENDING_TIMESTAMP_PREFIX + fileName))
            prefs.remove(stringPreferencesKey(PENDING_PATH_PREFIX + fileName))
        }
    }
}
