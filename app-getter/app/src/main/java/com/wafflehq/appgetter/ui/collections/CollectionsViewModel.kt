package com.wafflehq.appgetter.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.api.CollectedFile
import com.wafflehq.appgetter.data.discovery.NetworkDiscovery
import com.wafflehq.appgetter.data.install.ApkInstaller
import com.wafflehq.appgetter.data.install.DownloadStatus
import com.wafflehq.appgetter.data.settings.DownloadHistoryRepository
import com.wafflehq.appgetter.data.settings.PendingInstall
import com.wafflehq.appgetter.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CollectionsState {
    data object Scanning : CollectionsState
    data object NotFound : CollectionsState
    data class Found(val host: String, val port: Int, val files: List<CollectedFile>) : CollectionsState
}

data class CollectionsUiState(
    val state: CollectionsState = CollectionsState.Scanning,
    val error: String? = null,
    val installFile: File? = null,
    val downloadingFileName: String? = null,
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val discovery: NetworkDiscovery,
    private val api: AppGetterApi,
    private val installer: ApkInstaller,
    private val downloadHistoryRepository: DownloadHistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    val downloadStatus: StateFlow<DownloadStatus?> = installer.downloadStatus

    val downloadedTimestamps: StateFlow<Map<String, String>> = downloadHistoryRepository.downloadedTimestamps
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val pendingInstalls: StateFlow<List<PendingInstall>> = downloadHistoryRepository.pendingInstalls
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(state = CollectionsState.Scanning, error = null) }
            val override = settingsRepository.serverOverride.first()
            val host = override.host ?: discovery.discoverHost(override.port)
            if (host == null) {
                _uiState.update { it.copy(state = CollectionsState.NotFound) }
                return@launch
            }
            try {
                val files = api.getCollections(host, override.port).files
                _uiState.update { it.copy(state = CollectionsState.Found(host, override.port, files)) }
                prunePendingInstalls()
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(state = CollectionsState.NotFound, error = error.message ?: "Unbekannter Fehler.")
                }
            }
        }
    }

    private suspend fun prunePendingInstalls() {
        val pending = downloadHistoryRepository.pendingInstalls.first()
        pending.forEach { entry ->
            val cached = File(entry.filePath)
            if (!cached.exists() || cached.length() <= 0L) {
                downloadHistoryRepository.clearPendingInstall(entry.fileName)
            }
        }
    }

    /** Vergleicht den gecachten Eintrag mit [current] (frisch vom Server geladen); loescht die gecachte APK bei einer veralteten Version statt sie zum Installieren anzubieten. */
    private suspend fun cachedFileFor(current: CollectedFile): File? {
        val pending = downloadHistoryRepository.pendingInstalls.first()
        val entry = pending.find { it.fileName == current.name } ?: return null
        val cached = File(entry.filePath)
        if (entry.timestamp != current.timestamp) {
            cached.delete()
            downloadHistoryRepository.clearPendingInstall(entry.fileName)
            return null
        }
        if (cached.exists() && cached.length() > 0L) return cached
        downloadHistoryRepository.clearPendingInstall(entry.fileName)
        return null
    }

    fun downloadAndInstall(file: CollectedFile) {
        if (_uiState.value.downloadingFileName != null) return
        val found = _uiState.value.state as? CollectionsState.Found ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingFileName = file.name, error = null) }
            try {
                val current = try {
                    api.getCollections(found.host, found.port).files.find { it.name == file.name } ?: file
                } catch (error: ApiException) {
                    file
                }
                val cached = cachedFileFor(current)
                if (cached != null) {
                    _uiState.update { it.copy(installFile = cached, downloadingFileName = null, error = null) }
                    return@launch
                }
                val downloaded = installer.downloadFile(found.host, found.port, current.name)
                downloadHistoryRepository.recordDownload(current.name, current.timestamp)
                downloadHistoryRepository.recordPendingInstall(current.name, current.timestamp, downloaded.absolutePath)
                _uiState.update { it.copy(installFile = downloaded) }
            } catch (error: ApiException) {
                installer.clearDownloadStatus()
                _uiState.update {
                    it.copy(error = error.message ?: "Download fehlgeschlagen.", downloadingFileName = null)
                }
            }
        }
    }

    fun installIntent(file: File) = installer.installIntent(file)

    fun shareIntent(file: File) = installer.shareIntent(file)

    fun consumeInstallFile() {
        installer.clearDownloadStatus()
        _uiState.update { it.copy(installFile = null, downloadingFileName = null) }
    }

    fun resolveInstallFile(file: File) {
        viewModelScope.launch { downloadHistoryRepository.clearPendingInstall(file.name) }
        consumeInstallFile()
    }

    fun deleteInstallFile() {
        val file = _uiState.value.installFile ?: return
        viewModelScope.launch {
            file.delete()
            downloadHistoryRepository.clearPendingInstall(file.name)
            consumeInstallFile()
        }
    }
}
