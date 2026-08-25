package com.wafflehq.commander.ui.settings.contexts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.context.DevContextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextEditUiState(
    val nameInput: String = "",
    val valueInput: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

@HiltViewModel
class ContextEditViewModel @Inject constructor(
    private val repository: DevContextRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val id: Long? = savedStateHandle["id"]
    val isNew: Boolean = id == null

    private val _uiState = MutableStateFlow(ContextEditUiState(loading = !isNew))
    val uiState: StateFlow<ContextEditUiState> = _uiState.asStateFlow()

    init {
        val existingId = id
        if (existingId != null) {
            viewModelScope.launch {
                val context = repository.getById(existingId)
                _uiState.update {
                    it.copy(
                        nameInput = context?.name.orEmpty(),
                        valueInput = context?.value.orEmpty(),
                        loading = false,
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(nameInput = value) }
    }

    fun onValueChange(value: String) {
        _uiState.update { it.copy(valueInput = value) }
    }

    fun save() {
        val state = _uiState.value
        if (state.nameInput.isBlank() || state.valueInput.isBlank()) {
            _uiState.update { it.copy(error = "Name und Wert angeben.") }
            return
        }
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val existingId = id
            if (existingId != null) {
                repository.update(existingId, state.nameInput, state.valueInput)
            } else {
                repository.add(state.nameInput, state.valueInput)
            }
            _uiState.update { it.copy(saving = false, saved = true) }
        }
    }

    fun delete() {
        val existingId = id ?: return
        viewModelScope.launch {
            val context = repository.getById(existingId) ?: return@launch
            repository.delete(context)
            _uiState.update { it.copy(deleted = true) }
        }
    }
}
