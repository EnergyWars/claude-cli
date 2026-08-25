package com.wafflehq.commander.ui.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.FeedbackEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedbackUiState(
    val feedback: List<FeedbackEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val editingId: Int? = null,
    val editText: String = "",
    val projectNames: List<String> = emptyList(),
    val convertingEntry: FeedbackEntry? = null,
    val convertProjectIndex: Int = 0,
)

@HiltViewModel
class FeedbackListViewModel @Inject constructor(
    private val api: ClServerApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val feedback = api.getFeedback().feedback
                _uiState.update { it.copy(feedback = feedback, loading = false) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(loading = false, error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun startEdit(entry: FeedbackEntry) {
        _uiState.update { it.copy(editingId = entry.id, editText = entry.text) }
    }

    fun onEditTextChange(value: String) {
        _uiState.update { it.copy(editText = value) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingId = null, editText = "") }
    }

    fun saveEdit() {
        val id = _uiState.value.editingId ?: return
        val text = _uiState.value.editText
        viewModelScope.launch {
            try {
                val updated = api.updateFeedback(id, text)
                _uiState.update { state ->
                    state.copy(
                        feedback = state.feedback.map { if (it.id == id) updated else it },
                        editingId = null,
                        editText = "",
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun delete(entry: FeedbackEntry) {
        viewModelScope.launch {
            try {
                api.deleteFeedback(entry.id)
                _uiState.update { state -> state.copy(feedback = state.feedback.filterNot { it.id == entry.id }) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun startConvert(entry: FeedbackEntry) {
        viewModelScope.launch {
            try {
                val names = api.getManifest().paths.map { it.name }
                _uiState.update { it.copy(convertingEntry = entry, projectNames = names, convertProjectIndex = 0) }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.") }
            }
        }
    }

    fun onConvertProjectSelected(index: Int) {
        _uiState.update { it.copy(convertProjectIndex = index) }
    }

    fun cancelConvert() {
        _uiState.update { it.copy(convertingEntry = null) }
    }

    fun confirmConvert() {
        val state = _uiState.value
        val entry = state.convertingEntry ?: return
        val pathName = state.projectNames.getOrNull(state.convertProjectIndex) ?: return
        viewModelScope.launch {
            try {
                api.createTicket(pathName, entry.text)
                api.deleteFeedback(entry.id)
                _uiState.update { s ->
                    s.copy(
                        feedback = s.feedback.filterNot { it.id == entry.id },
                        convertingEntry = null,
                    )
                }
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.message ?: "Unbekannter Fehler.", convertingEntry = null) }
            }
        }
    }
}
