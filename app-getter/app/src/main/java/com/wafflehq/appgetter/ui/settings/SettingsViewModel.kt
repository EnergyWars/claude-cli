package com.wafflehq.appgetter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.appgetter.data.settings.DEFAULT_SERVER_PORT
import com.wafflehq.appgetter.data.settings.SettingsRepository
import com.wafflehq.appgetter.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val hostInput: String = "",
    val portInput: String = DEFAULT_SERVER_PORT.toString(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val override = settingsRepository.serverOverride.first()
            _uiState.update { it.copy(hostInput = override.host.orEmpty(), portInput = override.port.toString()) }
        }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun onHostChange(value: String) {
        _uiState.update { it.copy(hostInput = value) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(portInput = value) }
    }

    fun save() {
        val port = _uiState.value.portInput.toIntOrNull() ?: DEFAULT_SERVER_PORT
        viewModelScope.launch {
            settingsRepository.setServerOverride(_uiState.value.hostInput.ifBlank { null }, port)
        }
    }
}
