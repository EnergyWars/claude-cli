package com.wafflehq.appgetter.ui.settings

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
    val text: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
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

    fun onTextChange(value: String) {
        _uiState.update { it.copy(text = value, sent = false) }
    }

    fun send() {
        val text = _uiState.value.text.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(sending = true, error = null, sent = false) }
            val override = settingsRepository.serverOverride.first()
            val host = override.host ?: discovery.discoverHost(override.port)
            if (host == null) {
                _uiState.update { it.copy(sending = false, error = "Kein Server gefunden.") }
                return@launch
            }
            try {
                api.sendFeedback(host, override.port, text)
                _uiState.update { it.copy(sending = false, sent = true, text = "") }
            } catch (error: ApiException) {
                _uiState.update { it.copy(sending = false, error = error.message ?: "Senden fehlgeschlagen.") }
            }
        }
    }
}
