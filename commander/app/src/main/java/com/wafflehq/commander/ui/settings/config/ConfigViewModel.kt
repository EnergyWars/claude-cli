package com.wafflehq.commander.ui.settings.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class ConfigUiState(
    val jsonInput: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val prettyJson = Json { prettyPrint = true }

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val config = api.getConfig()
                _uiState.update { it.copy(jsonInput = prettify(config), loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onJsonChange(value: String) {
        _uiState.update { it.copy(jsonInput = value, saved = false) }
    }

    fun save() {
        val jsonInput = _uiState.value.jsonInput
        try {
            Json.parseToJsonElement(jsonInput)
        } catch (error: SerializationException) {
            _uiState.update { it.copy(error = "Ungültiges JSON.") }
            return
        }
        _uiState.update { it.copy(saving = true, error = null, warning = null) }
        viewModelScope.launch {
            try {
                val response = api.putConfig(jsonInput)
                _uiState.update {
                    it.copy(
                        saving = false,
                        saved = true,
                        warning = response.warning,
                        jsonInput = prettify(response.config),
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(saving = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    private fun prettify(element: JsonElement): String = prettyJson.encodeToString(JsonElement.serializer(), element)
}
