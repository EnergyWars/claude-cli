package com.wafflehq.commander.ui.downloads

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.download.DownloadHistoryRepository
import com.wafflehq.commander.data.download.DownloadVersion
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadHistoryGroup(val identity: String, val versions: List<DownloadVersion>)

fun groupDownloadHistory(all: List<DownloadVersion>, pathName: String): List<DownloadHistoryGroup> {
    val prefix = "$pathName::"
    return all.filter { it.key.startsWith(prefix) }
        .groupBy { it.key.removePrefix(prefix) }
        .map { (identity, versions) -> DownloadHistoryGroup(identity, versions.reversed()) }
        .sortedBy { it.identity }
}

@HiltViewModel
class DownloadHistoryViewModel @Inject constructor(
    private val historyRepository: DownloadHistoryRepository,
    private val downloader: HostedFileDownloader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pathName: String = checkNotNull(savedStateHandle["pathName"])

    val groups: StateFlow<List<DownloadHistoryGroup>> = historyRepository.versions
        .map { groupDownloadHistory(it, pathName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun delete(identity: String, version: DownloadVersion) {
        viewModelScope.launch {
            downloader.deletePendingInstall(pathName, identity, File(version.filePath))
        }
    }

    fun openOrInstallIntent(version: DownloadVersion): Intent = downloader.openOrInstallIntent(File(version.filePath))
}
