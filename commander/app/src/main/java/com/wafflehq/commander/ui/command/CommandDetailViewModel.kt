package com.wafflehq.commander.ui.command

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.api.HOSTED_TYPE_FILE
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 2_000L
const val COMMAND_STATUS_RUNNING = "running"

data class CommandDetailUiState(
    val state: CommandState? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val hostedFiles: List<String> = emptyList(),
    val downloadedFile: File? = null,
    val downloadingName: String? = null,
    val stopping: Boolean = false,
)

@HiltViewModel
class CommandDetailViewModel @Inject constructor(
    private val api: ClServerApi,
    private val downloader: HostedFileDownloader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val id: String = checkNotNull(savedStateHandle["id"])
    private val pathName: String? = savedStateHandle["pathName"]

    private val _uiState = MutableStateFlow(CommandDetailUiState())
    val uiState: StateFlow<CommandDetailUiState> = _uiState.asStateFlow()

    val downloadStatus: StateFlow<DownloadStatus?> = downloader.downloadStatus

    val pendingInstalls: StateFlow<Set<String>> = downloader.pendingInstalls
        .map { list ->
            val name = pathName ?: return@map emptySet()
            list.filter { it.key.startsWith("$name::") }
                .map { it.key.removePrefix("$name::") }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        loadHostedFiles()
        viewModelScope.launch {
            var reachedTerminalStatus = false
            try {
                api.streamState(id).collect { state ->
                    _uiState.update { it.copy(state = state, loading = false, error = null) }
                    if (state.status != COMMAND_STATUS_RUNNING) reachedTerminalStatus = true
                }
            } catch (_: ApiException) {
                // SSE-Verbindung abgebrochen, bevor ein Endstatus ankam (z.B. Proxy ohne Streaming-Unterstuetzung) -
                // Fallback auf Polling unten.
            }
            while (isActive && !reachedTerminalStatus) {
                try {
                    val state = api.getState(id)
                    _uiState.update { it.copy(state = state, loading = false, error = null) }
                    if (state.status != COMMAND_STATUS_RUNNING) reachedTerminalStatus = true
                } catch (error: ApiException) {
                    _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
                    reachedTerminalStatus = true
                }
                if (!reachedTerminalStatus) delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun loadHostedFiles() {
        val name = pathName ?: return
        viewModelScope.launch {
            try {
                val hostedFileNames = api.getManifest().paths
                    .firstOrNull { it.name == name }
                    ?.hosted
                    ?.filter { it.type == HOSTED_TYPE_FILE }
                    ?.map { it.name }
                    .orEmpty()
                _uiState.update { it.copy(hostedFiles = hostedFileNames) }
            } catch (_: ApiException) {
                // Hosted-Downloads sind ein Zusatzangebot - kein Fehler in der Haupt-Statusanzeige.
            }
        }
    }

    fun download(hostedName: String) {
        val name = pathName ?: return
        if (_uiState.value.downloadingName != null) return
        viewModelScope.launch {
            val cached = downloader.resolvePendingInstall(name, hostedName)
            if (cached != null) {
                _uiState.update { it.copy(downloadingName = hostedName, downloadedFile = cached, error = null) }
                return@launch
            }
            _uiState.update { it.copy(downloadingName = hostedName, error = null) }
            try {
                val file = downloader.downloadEntry(name, hostedName)
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

    fun deleteDownloadedFile() {
        val file = _uiState.value.downloadedFile ?: return
        val name = pathName ?: return
        val identity = _uiState.value.downloadingName ?: return
        viewModelScope.launch {
            downloader.deletePendingInstall(name, identity, file)
            consumeDownloadedFile()
        }
    }

    fun stop() {
        if (_uiState.value.stopping) return
        viewModelScope.launch {
            _uiState.update { it.copy(stopping = true, error = null) }
            try {
                api.stopCommand(id)
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Stoppen fehlgeschlagen.") }
            }
            _uiState.update { it.copy(stopping = false) }
        }
    }

    fun openOrInstallIntent(file: File) = downloader.openOrInstallIntent(file)

    fun installIntent(file: File) = downloader.installIntent(file)

    fun shareApkIntent(file: File) = downloader.shareApkIntent(file)
}
