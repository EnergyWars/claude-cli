package com.wafflehq.commander.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.connection.Connection
import com.wafflehq.commander.data.connection.ConnectionRepository
import com.wafflehq.commander.data.totp.TotpGenerator
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
    data object NeedsManualSecret : SetupStatus
    data class Error(val message: String) : SetupStatus
    data object Connected : SetupStatus
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val api: ClServerApi,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<String> = _port.asStateFlow()

    private val _manualSecret = MutableStateFlow("")
    val manualSecret: StateFlow<String> = _manualSecret.asStateFlow()

    private val _status = MutableStateFlow<SetupStatus>(SetupStatus.Idle)
    val status: StateFlow<SetupStatus> = _status.asStateFlow()

    fun onHostChange(value: String) {
        _host.value = value
    }

    fun onPortChange(value: String) {
        _port.value = value.filter(Char::isDigit)
    }

    fun onManualSecretChange(value: String) {
        _manualSecret.value = value.trim()
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
                val authStatus = api.authStatus(host, portInt)
                if (authStatus.active) {
                    _status.value = SetupStatus.NeedsManualSecret
                    return@launch
                }
                val setup = api.setupAuth(host, portInt)
                val code = TotpGenerator.generate(setup.secret)
                api.confirmAuthSetup(host, portInt, code)
                connectionRepository.save(Connection(host, portInt, setup.secret))
                _status.value = SetupStatus.Connected
            } catch (error: ApiException) {
                _status.value = SetupStatus.Error(error.message ?: "Unbekannter Fehler.")
            }
        }
    }

    fun connectWithManualSecret() {
        val portInt = _port.value.toIntOrNull() ?: return
        val host = _host.value.trim()
        val secret = _manualSecret.value
        if (secret.isEmpty()) {
            _status.value = SetupStatus.Error("Secret angeben.")
            return
        }
        viewModelScope.launch {
            _status.value = SetupStatus.Checking
            try {
                val valid = api.verifySecret(host, portInt, secret)
                if (valid) {
                    connectionRepository.save(Connection(host, portInt, secret))
                    _status.value = SetupStatus.Connected
                } else {
                    _status.value = SetupStatus.Error("Secret ungueltig.")
                }
            } catch (error: ApiException) {
                _status.value = SetupStatus.Error(error.message ?: "Unbekannter Fehler.")
            }
        }
    }

    fun dismissError() {
        if (_status.value is SetupStatus.Error) {
            _status.value = SetupStatus.Idle
        }
    }
}
