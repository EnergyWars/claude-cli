package com.wafflehq.commander.ui.downloads

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val hosted: List<ManifestHostedEntry> = emptyList(),
    val expandedHostedFiles: Map<String, List<String>> = emptyMap(),
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

    init {
        refresh()
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
            try {
                val file = downloader.downloadEntry(pathName, hostedName)
                _uiState.update { it.copy(downloadedFile = file) }
            } catch (error: ApiException) {
                downloader.clearDownloadStatus()
                _uiState.update { it.copy(error = error.message ?: "Download fehlgeschlagen.", downloadingName = null) }
            }
        }
    }

    fun downloadNestedFile(hostedName: String, fileName: String) {
        if (_uiState.value.downloadingName != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingName = fileName, error = null) }
            try {
                val file = downloader.downloadFile(pathName, hostedName, fileName)
                _uiState.update { it.copy(downloadedFile = file) }
            } catch (error: ApiException) {
                downloader.clearDownloadStatus()
                _uiState.update { it.copy(error = error.message ?: "Download fehlgeschlagen.", downloadingName = null) }
            }
        }
    }

    fun consumeDownloadedFile() {
        downloader.clearDownloadStatus()
        _uiState.update { it.copy(downloadedFile = null, downloadingName = null) }
    }

    fun openOrInstallIntent(file: File): Intent = downloader.openOrInstallIntent(file)
}
