package com.wafflehq.commander.ui.setup

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.connection.ConnectionRepository
import com.wafflehq.commander.data.discovery.NetworkDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SetupErrorMessage {
    data class Text(val value: String) : SetupErrorMessage
    data class Resource(@param:StringRes val id: Int) : SetupErrorMessage
}

sealed interface SetupStatus {
    data object Idle : SetupStatus
    data object Checking : SetupStatus
    data object Discovering : SetupStatus
    data class Error(val message: SetupErrorMessage) : SetupStatus
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

    private val _portOption = MutableStateFlow(PortOption.Test)
    val portOption: StateFlow<PortOption> = _portOption.asStateFlow()

    private val _customPort = MutableStateFlow("")
    val customPort: StateFlow<String> = _customPort.asStateFlow()

    private val _newHost = MutableStateFlow(false)

    private val _status = MutableStateFlow<SetupStatus>(SetupStatus.Idle)
    val status: StateFlow<SetupStatus> = _status.asStateFlow()

    val hostHistory: StateFlow<List<String>> = connectionRepository.hostHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Das Eingabefeld ersetzt die Auswahl, solange es keine gemerkten Adressen gibt oder "Neu" gewaehlt wurde. */
    val hostInputVisible: StateFlow<Boolean> = combine(hostHistory, _newHost) { history, new ->
        new || history.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            val remembered = connectionRepository.hostHistory.first()
            if (remembered.isNotEmpty() && _host.value.isEmpty() && !_newHost.value) {
                _host.value = remembered.first()
            }
        }
    }

    fun onHostChange(value: String) {
        _host.value = value
    }

    fun onHostSelected(value: String) {
        _newHost.value = false
        _host.value = value
    }

    fun onNewHostSelected() {
        _newHost.value = true
        _host.value = ""
    }

    fun onPortOptionChange(option: PortOption) {
        _portOption.value = option
    }

    fun onCustomPortChange(value: String) {
        _customPort.value = value.filter(Char::isDigit).take(5)
    }

    fun connect() {
        val port = resolvePort(_portOption.value, _customPort.value)
        val host = _host.value.trim()
        if (host.isEmpty()) {
            _status.value = SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_host_missing))
            return
        }
        if (port == null) {
            _status.value = SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_port_invalid))
            return
        }
        viewModelScope.launch {
            _status.value = SetupStatus.Checking
            try {
                api.health(host, port)
                connectionRepository.saveConnection(host, port)
                _status.value = SetupStatus.Connected
            } catch (error: ApiException) {
                _status.value = SetupStatus.Error(
                    error.message
                        ?.let { SetupErrorMessage.Text(it) }
                        ?: SetupErrorMessage.Resource(R.string.setup_error_unknown),
                )
            }
        }
    }

    fun discover() {
        val port = resolvePort(_portOption.value, _customPort.value)
        if (port == null) {
            _status.value = SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_port_invalid))
            return
        }
        viewModelScope.launch {
            _status.value = SetupStatus.Discovering
            val found = networkDiscovery.discoverHost(port)
            if (found != null) {
                _newHost.value = true
                _host.value = found
                _status.value = SetupStatus.Idle
            } else {
                _status.value = SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_not_found))
            }
        }
    }

    fun dismissError() {
        if (_status.value is SetupStatus.Error) {
            _status.value = SetupStatus.Idle
        }
    }
}
