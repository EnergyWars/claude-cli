package com.wafflehq.commander.ui.command

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.api.HOSTED_TYPE_FILE
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        loadHostedFiles()
        viewModelScope.launch {
            while (isActive) {
                try {
                    val state = api.getState(id)
                    _uiState.update { it.copy(state = state, loading = false, error = null) }
                    if (state.status != COMMAND_STATUS_RUNNING) break
                } catch (error: ApiException) {
                    _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
                    break
                }
                delay(POLL_INTERVAL_MS)
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
        viewModelScope.launch {
            try {
                val file = downloader.downloadEntry(name, hostedName)
                _uiState.update { it.copy(downloadedFile = file) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Download fehlgeschlagen.") }
            }
        }
    }

    fun consumeDownloadedFile() {
        _uiState.update { it.copy(downloadedFile = null) }
    }

    fun openOrInstallIntent(file: File) = downloader.openOrInstallIntent(file)
}
