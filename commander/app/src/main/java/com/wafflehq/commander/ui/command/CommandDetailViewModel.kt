package com.wafflehq.commander.ui.command

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.api.HOSTED_TYPE_FILE
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.download.DownloadOutcome
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
    val hostedFiles: List<ManifestHostedEntry> = emptyList(),
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
        resumeActiveDownload()
        observeDownloadOutcome()
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
                }
                if (!reachedTerminalStatus) delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Ein Download laeuft prozessweit weiter, auch wenn dieser Screen zwischenzeitlich geschlossen war - beim Wiedereintritt den Anzeigezustand daran ausrichten. */
    private fun resumeActiveDownload() {
        val name = pathName ?: return
        val target = downloader.activeTarget.value
        if (target != null && target.pathName == name) {
            _uiState.update { it.copy(downloadingName = target.identity) }
        }
    }

    private fun observeDownloadOutcome() {
        viewModelScope.launch {
            downloader.downloadOutcome.collect { outcome ->
                val name = pathName ?: return@collect
                if (outcome == null || outcome.target.pathName != name) return@collect
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

    private fun loadHostedFiles() {
        val name = pathName ?: return
        viewModelScope.launch {
            try {
                val hostedFileEntries = api.getManifest().paths
                    .firstOrNull { it.name == name }
                    ?.hosted
                    ?.filter { it.type == HOSTED_TYPE_FILE }
                    .orEmpty()
                _uiState.update { it.copy(hostedFiles = hostedFileEntries) }
            } catch (_: ApiException) {
                // Hosted-Downloads sind ein Zusatzangebot - kein Fehler in der Haupt-Statusanzeige.
            }
        }
    }

    fun download(hostedName: String) {
        val name = pathName ?: return
        if (_uiState.value.downloadingName != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingName = hostedName, error = null) }
            val timestamp = currentHostedTimestamp(name, hostedName)
            val cached = downloader.resolvePendingInstall(name, hostedName, timestamp)
            if (cached != null) {
                _uiState.update { it.copy(downloadedFile = cached, error = null) }
                return@launch
            }
            val started = downloader.startDownload(name, hostedName, fileName = null, timestamp = timestamp)
            if (!started) {
                _uiState.update { it.copy(error = "Ein anderer Download läuft bereits.", downloadingName = null) }
            }
        }
    }

    /** Fragt den aktuellen Server-Stand direkt ab, statt sich auf das zuletzt in [loadHostedFiles] geladene Manifest zu verlassen - nur so laesst sich eine gecachte APK verlaesslich gegen eine inzwischen geaenderte Server-Version pruefen. */
    private suspend fun currentHostedTimestamp(name: String, hostedName: String): String? =
        try {
            api.getManifest().paths.firstOrNull { it.name == name }
                ?.hosted?.firstOrNull { it.name == hostedName && it.type == HOSTED_TYPE_FILE }?.timestamp
        } catch (error: ApiException) {
            null
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
