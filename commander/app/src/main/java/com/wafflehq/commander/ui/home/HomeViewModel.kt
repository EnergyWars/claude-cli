package com.wafflehq.commander.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.db.CommandHistoryEntity
import com.wafflehq.commander.data.history.CommandHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ManifestState {
    data object Loading : ManifestState
    data class Loaded(val manifest: Manifest) : ManifestState
    data class Error(val message: String) : ManifestState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: ClServerApi,
    historyRepository: CommandHistoryRepository,
) : ViewModel() {

    private val _manifestState = MutableStateFlow<ManifestState>(ManifestState.Loading)
    val manifestState: StateFlow<ManifestState> = _manifestState.asStateFlow()

    val history: StateFlow<List<CommandHistoryEntity>> = historyRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _manifestState.value = ManifestState.Loading
            _manifestState.value = try {
                ManifestState.Loaded(api.getManifest())
            } catch (error: ApiException) {
                ManifestState.Error(error.message ?: "Unbekannter Fehler.")
            }
        }
    }
}
