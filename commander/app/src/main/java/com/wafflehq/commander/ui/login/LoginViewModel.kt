package com.wafflehq.commander.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.connection.ConnectionRepository
import com.wafflehq.commander.data.connection.ConnectionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LoginStatus {
    data object Idle : LoginStatus
    data object Checking : LoginStatus
    data class Error(val message: String) : LoginStatus
    data object LoggedIn : LoginStatus
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: ClServerApi,
    private val connectionRepository: ConnectionRepository,
    private val connectionSource: ConnectionSource,
) : ViewModel() {

    val connectionLabel: StateFlow<String?> = connectionSource.session
        .map { session -> session?.connection?.let { "${it.host}:${it.port}" } }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    private val _status = MutableStateFlow<LoginStatus>(LoginStatus.Idle)
    val status: StateFlow<LoginStatus> = _status.asStateFlow()

    fun onCodeChange(value: String) {
        _code.value = value.filter(Char::isDigit).take(6)
    }

    fun submit() {
        if (_code.value.length != 6) {
            _status.value = LoginStatus.Error("6-stelligen Code eingeben.")
            return
        }
        viewModelScope.launch {
            val connection = connectionSource.session.first()?.connection
            if (connection == null) {
                _status.value = LoginStatus.Error("Keine Verbindung konfiguriert.")
                return@launch
            }
            _status.value = LoginStatus.Checking
            try {
                val authStatus = api.authStatus(connection.host, connection.port)
                val response = if (authStatus.pending) {
                    api.confirmAuthSetup(connection.host, connection.port, _code.value)
                } else {
                    api.login(connection.host, connection.port, _code.value)
                }
                connectionRepository.saveAuthSession(response.token, Instant.parse(response.expiresAt))
                _status.value = LoginStatus.LoggedIn
            } catch (error: ApiException) {
                _status.value = LoginStatus.Error(error.message ?: "Unbekannter Fehler.")
            }
        }
    }

    fun changeConnection() {
        viewModelScope.launch {
            connectionRepository.clear()
        }
    }

    fun dismissError() {
        if (_status.value is LoginStatus.Error) {
            _status.value = LoginStatus.Idle
        }
    }
}
