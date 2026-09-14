package com.wafflehq.commander.ui.costs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CostOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TokenCostsUiState(
    val data: CostOverview? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class TokenCostsViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TokenCostsUiState())
    val uiState: StateFlow<TokenCostsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val data = api.getCosts()
                _uiState.update { it.copy(data = data, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }
}
