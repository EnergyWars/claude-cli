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

@Singleton
class DownloadHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val downloadedTimestamps: Flow<Map<String, String>> = context.downloadHistoryDataStore.data.map { prefs ->
        prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() }
    }

    suspend fun recordDownload(fileName: String, timestamp: String) {
        context.downloadHistoryDataStore.edit { prefs -> prefs[stringPreferencesKey(fileName)] = timestamp }
    }
}
