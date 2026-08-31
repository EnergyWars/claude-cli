package com.wafflehq.commander.ui.downloads

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.HostedFileEntry
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.download.DownloadOutcome
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val hosted: List<ManifestHostedEntry> = emptyList(),
    val expandedHostedFiles: Map<String, List<HostedFileEntry>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    val downloadedFile: File? = null,
    val downloadingName: String? = null,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val api: ClServerApi,
    private val downloader: HostedFileDownloader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    val downloadStatus: StateFlow<DownloadStatus?> = downloader.downloadStatus

    val pendingInstalls: StateFlow<Set<String>> = downloader.pendingInstalls
        .map { list ->
            list.filter { it.key.startsWith("$pathName::") }
                .map { it.key.removePrefix("$pathName::") }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        refresh()
        resumeActiveDownload()
        observeDownloadOutcome()
    }

    /** Ein Download laeuft prozessweit weiter, auch wenn dieser Screen zwischenzeitlich geschlossen war - beim Wiedereintritt den Anzeigezustand daran ausrichten. */
    private fun resumeActiveDownload() {
        val target = downloader.activeTarget.value
        if (target != null && target.pathName == pathName) {
            _uiState.update { it.copy(downloadingName = target.identity) }
        }
    }

    private fun observeDownloadOutcome() {
        viewModelScope.launch {
            downloader.downloadOutcome.collect { outcome ->
                if (outcome == null || outcome.target.pathName != pathName) return@collect
                when (outcome) {
                    is DownloadOutcome.Success -> _uiState.update {
                        it.copy(downloadingName = outcome.target.identity, downloadedFile = outcome.file, error = null)
                    }
                    is DownloadOutcome.Failure -> _uiState.update {
                        it.copy(downloadingName = null, error = outcome.message)
                    }
                }
                downloader.consumeDownloadOutcome()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val hosted = api.getManifest().paths.firstOrNull { it.name == pathName }?.hosted.orEmpty()
                _uiState.update { it.copy(hosted = hosted, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun toggleExpandHosted(hostedName: String) {
        val state = _uiState.value
        if (state.expandedHostedFiles.containsKey(hostedName)) {
            _uiState.update { it.copy(expandedHostedFiles = it.expandedHostedFiles - hostedName) }
            return
        }
        viewModelScope.launch {
            try {
                val files = api.listHostedFiles(pathName, hostedName).files
                _uiState.update { it.copy(expandedHostedFiles = it.expandedHostedFiles + (hostedName to files)) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun downloadEntry(hostedName: String) {
        if (_uiState.value.downloadingName != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingName = hostedName, error = null) }
            val timestamp = currentHostedTimestamp(hostedName)
            val cached = downloader.resolvePendingInstall(pathName, hostedName, timestamp)
            if (cached != null) {
                _uiState.update { it.copy(downloadedFile = cached, error = null) }
                return@launch
            }
            val started = downloader.startDownload(pathName, hostedName, fileName = null, timestamp = timestamp)
            if (!started) {
                _uiState.update { it.copy(error = "Ein anderer Download läuft bereits.", downloadingName = null) }
            }
        }
    }

    fun downloadNestedFile(hostedName: String, fileName: String) {
        if (_uiState.value.downloadingName != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingName = fileName, error = null) }
            val timestamp = currentNestedFileTimestamp(hostedName, fileName)
            val cached = downloader.resolvePendingInstall(pathName, fileName, timestamp)
            if (cached != null) {
                _uiState.update { it.copy(downloadedFile = cached, error = null) }
                return@launch
            }
            val started = downloader.startDownload(pathName, hostedName, fileName = fileName, timestamp = timestamp)
            if (!started) {
                _uiState.update { it.copy(error = "Ein anderer Download läuft bereits.", downloadingName = null) }
            }
        }
    }

    /** Fragt den aktuellen Server-Stand direkt ab, statt sich auf den zuletzt geladenen [DownloadsUiState.hosted] zu verlassen - nur so laesst sich eine gecachte APK verlaesslich gegen eine inzwischen geaenderte Server-Version pruefen. */
    private suspend fun currentHostedTimestamp(hostedName: String): String? =
        try {
            api.getManifest().paths.firstOrNull { it.name == pathName }
                ?.hosted?.firstOrNull { it.name == hostedName }?.timestamp
        } catch (error: ApiException) {
            null
        }

    private suspend fun currentNestedFileTimestamp(hostedName: String, fileName: String): String? =
        try {
            api.listHostedFiles(pathName, hostedName).files.firstOrNull { it.name == fileName }?.timestamp
        } catch (error: ApiException) {
            null
        }

    fun consumeDownloadedFile() {
        downloader.clearDownloadStatus()
        _uiState.update { it.copy(downloadedFile = null, downloadingName = null) }
    }

    fun deleteDownloadedFile() {
        val file = _uiState.value.downloadedFile ?: return
        val identity = _uiState.value.downloadingName ?: return
        viewModelScope.launch {
            downloader.deletePendingInstall(pathName, identity, file)
            consumeDownloadedFile()
        }
    }

    fun openOrInstallIntent(file: File): Intent = downloader.openOrInstallIntent(file)

    fun installIntent(file: File): Intent = downloader.installIntent(file)

    fun shareApkIntent(file: File): Intent = downloader.shareApkIntent(file)
}
