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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.downloadHistoryDataStore by preferencesDataStore(name = "download_history")

private val VERSIONS_KEY = stringPreferencesKey("versions")
private val historyJson = Json { ignoreUnknownKeys = true }

const val MAX_DOWNLOAD_VERSIONS_PER_KEY = 3

@Serializable
data class DownloadVersion(val key: String, val timestamp: String?, val downloadedAt: String, val filePath: String)

fun decodeDownloadVersions(raw: String?): List<DownloadVersion> =
    if (raw.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { historyJson.decodeFromString<List<DownloadVersion>>(raw) }.getOrDefault(emptyList())
    }

fun encodeDownloadVersions(versions: List<DownloadVersion>): String = historyJson.encodeToString(versions)

/**
 * Haengt [newEntry] ans Ende der Versionen mit demselben Key an, statt eine bestehende zu ueberschreiben - so bleiben
 * bis zu [maxPerKey] Versionen pro Datei erhalten. Der Aufrufer muss die im zweiten Wert zurueckgegebenen, dadurch
 * verdraengten Versionen selbst von der Platte loeschen.
 */
fun applyDownloadVersion(
    current: List<DownloadVersion>,
    newEntry: DownloadVersion,
    maxPerKey: Int = MAX_DOWNLOAD_VERSIONS_PER_KEY,
): Pair<List<DownloadVersion>, List<DownloadVersion>> {
    val forKey = current.filter { it.key == newEntry.key } + newEntry
    val kept = forKey.takeLast(maxPerKey)
    val evicted = forKey.dropLast(kept.size)
    val updated = current.filterNot { it.key == newEntry.key } + kept
    return updated to evicted
}

@Singleton
class DownloadHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val versions: Flow<List<DownloadVersion>> = context.downloadHistoryDataStore.data.map { prefs ->
        decodeDownloadVersions(prefs[VERSIONS_KEY])
    }

    suspend fun recordVersion(key: String, timestamp: String?, downloadedAt: String, filePath: String): List<DownloadVersion> {
        var evicted: List<DownloadVersion> = emptyList()
        context.downloadHistoryDataStore.edit { prefs ->
            val current = decodeDownloadVersions(prefs[VERSIONS_KEY])
            val (updated, evictedEntries) = applyDownloadVersion(current, DownloadVersion(key, timestamp, downloadedAt, filePath))
            evicted = evictedEntries
            prefs[VERSIONS_KEY] = encodeDownloadVersions(updated)
        }
        return evicted
    }

    suspend fun deleteVersion(key: String, filePath: String) {
        context.downloadHistoryDataStore.edit { prefs ->
            val current = decodeDownloadVersions(prefs[VERSIONS_KEY])
            prefs[VERSIONS_KEY] = encodeDownloadVersions(current.filterNot { it.key == key && it.filePath == filePath })
        }
    }
}
