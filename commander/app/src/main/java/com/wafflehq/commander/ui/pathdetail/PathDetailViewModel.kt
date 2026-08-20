package com.wafflehq.commander.ui.pathdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.api.PathCommandEntry
import com.wafflehq.commander.data.download.HostedFileDownloader
import com.wafflehq.commander.data.history.CommandHistoryRepository
import com.wafflehq.commander.data.history.CommandKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PathDetailUiState(
    val commands: List<PathCommandEntry> = emptyList(),
    val hosted: List<ManifestHostedEntry> = emptyList(),
    val expandedHostedFiles: Map<String, List<String>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    val startedCommandId: String? = null,
    val downloadedFile: File? = null,
)

@HiltViewModel
class PathDetailViewModel @Inject constructor(
    private val api: ClServerApi,
    private val downloader: HostedFileDownloader,
    private val historyRepository: CommandHistoryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pathName: String = checkNotNull(savedStateHandle["pathName"])

    private val _uiState = MutableStateFlow(PathDetailUiState())
    val uiState: StateFlow<PathDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val pathEntry = api.getManifest().paths.firstOrNull { it.name == pathName }
                _uiState.update {
                    it.copy(
                        commands = pathEntry?.commands.orEmpty(),
                        hosted = pathEntry?.hosted.orEmpty(),
                        loading = false,
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun runCommand(key: String) {
        viewModelScope.launch {
            try {
                val accepted = api.runPathCommand(pathName, key)
                historyRepository.record(accepted.id, CommandKind.PATH_COMMAND, key, pathName)
                _uiState.update { it.copy(startedCommandId = accepted.id) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
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
        viewModelScope.launch {
            try {
                val file = downloader.downloadEntry(pathName, hostedName)
                _uiState.update { it.copy(downloadedFile = file) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Download fehlgeschlagen.") }
            }
        }
    }

    fun downloadNestedFile(hostedName: String, fileName: String) {
        viewModelScope.launch {
            try {
                val file = downloader.downloadFile(pathName, hostedName, fileName)
                _uiState.update { it.copy(downloadedFile = file) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Download fehlgeschlagen.") }
            }
        }
    }

    fun consumeDownloadedFile() {
        _uiState.update { it.copy(downloadedFile = null) }
    }

    fun consumeStartedCommand() {
        _uiState.update { it.copy(startedCommandId = null) }
    }

    fun shareIntent(file: File) = downloader.shareIntent(file)
}
