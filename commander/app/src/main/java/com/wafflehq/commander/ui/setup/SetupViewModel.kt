package com.wafflehq.commander.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.connection.ConnectionRepository
import com.wafflehq.commander.data.discovery.NetworkDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEFAULT_PORT = "8787"

sealed interface SetupStatus {
    data object Idle : SetupStatus
    data object Checking : SetupStatus
    data object Discovering : SetupStatus
    data class Error(val message: String) : SetupStatus
    data object Connected : SetupStatus
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val api: ClServerApi,
    private val connectionRepository: ConnectionRepository,
    private val networkDiscovery: NetworkDiscovery,
) : ViewModel() {

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<String> = _port.asStateFlow()

    private val _status = MutableStateFlow<SetupStatus>(SetupStatus.Idle)
    val status: StateFlow<SetupStatus> = _status.asStateFlow()

    fun onHostChange(value: String) {
        _host.value = value
    }

    fun onPortChange(value: String) {
        _port.value = value.filter(Char::isDigit)
    }

    fun connect() {
        val portInt = _port.value.toIntOrNull()
        val host = _host.value.trim()
        if (host.isEmpty() || portInt == null) {
            _status.value = SetupStatus.Error("Host und Port angeben.")
            return
        }
        viewModelScope.launch {
            _status.value = SetupStatus.Checking
            try {
                api.health(host, portInt)
                connectionRepository.saveConnection(host, portInt)
                _status.value = SetupStatus.Connected
            } catch (error: ApiException) {
                _status.value = SetupStatus.Error(error.message ?: "Unbekannter Fehler.")
            }
        }
    }

    fun discover() {
        val portInt = _port.value.toIntOrNull()
        if (portInt == null) {
            _status.value = SetupStatus.Error("Port angeben.")
            return
        }
        viewModelScope.launch {
            _status.value = SetupStatus.Discovering
            val found = networkDiscovery.discoverHost(portInt)
            if (found != null) {
                _host.value = found
                _status.value = SetupStatus.Idle
            } else {
                _status.value = SetupStatus.Error("Kein Server im lokalen Netz gefunden.")
            }
        }
    }

    fun dismissError() {
        if (_status.value is SetupStatus.Error) {
            _status.value = SetupStatus.Idle
        }
    }
}
