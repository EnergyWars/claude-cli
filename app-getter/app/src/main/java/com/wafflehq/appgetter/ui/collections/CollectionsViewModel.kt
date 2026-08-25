package com.wafflehq.appgetter.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.api.CollectedFile
import com.wafflehq.appgetter.data.discovery.NetworkDiscovery
import com.wafflehq.appgetter.data.install.ApkInstaller
import com.wafflehq.appgetter.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val discovery: NetworkDiscovery,
    private val api: AppGetterApi,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

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
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(state = CollectionsState.NotFound, error = error.message ?: "Unbekannter Fehler.")
                }
            }
        }
    }

    fun downloadAndInstall(file: CollectedFile) {
        val found = _uiState.value.state as? CollectionsState.Found ?: return
        viewModelScope.launch {
            try {
                val downloaded = installer.downloadFile(found.host, found.port, file.name)
                _uiState.update { it.copy(installFile = downloaded) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Download fehlgeschlagen.") }
            }
        }
    }

    fun installIntent(file: File) = installer.installIntent(file)

    fun consumeInstallFile() {
        _uiState.update { it.copy(installFile = null) }
    }
}
