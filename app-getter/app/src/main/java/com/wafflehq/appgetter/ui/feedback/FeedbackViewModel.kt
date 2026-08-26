package com.wafflehq.appgetter.ui.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.discovery.NetworkDiscovery
import com.wafflehq.appgetter.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedbackUiState(
    val openSection: String? = null,
    val text: String = "",
    val context: String = "",
    val error: String? = null,
)

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val discovery: NetworkDiscovery,
    private val api: AppGetterApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    fun open(section: String, context: String = "") {
        _uiState.update { it.copy(openSection = section, text = "", context = context, error = null) }
    }

    fun dismiss() {
        _uiState.update { it.copy(openSection = null, text = "", context = "") }
    }

    fun onTextChange(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    fun onContextChange(value: String) {
        _uiState.update { it.copy(context = value) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun send() {
        val state = _uiState.value
        val section = state.openSection ?: return
        val text = state.text.trim()
        if (text.isEmpty()) return
        val context = state.context.trim().ifEmpty { null }
        _uiState.update { it.copy(openSection = null, text = "", context = "") }
        viewModelScope.launch {
            val override = settingsRepository.serverOverride.first()
            val host = override.host ?: discovery.discoverHost(override.port)
            if (host == null) {
                _uiState.update { it.copy(error = "Kein Server gefunden.") }
                return@launch
            }
            try {
                api.sendFeedback(host, override.port, text, section, context)
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Senden fehlgeschlagen.") }
            }
        }
    }
}
