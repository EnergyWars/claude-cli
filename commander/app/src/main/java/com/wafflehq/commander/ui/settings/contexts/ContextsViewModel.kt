package com.wafflehq.commander.ui.settings.contexts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wafflehq.commander.data.context.DevContextRepository
import com.wafflehq.commander.data.db.DevContextEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ContextsViewModel @Inject constructor(
    devContextRepository: DevContextRepository,
) : ViewModel() {

    val contexts: StateFlow<List<DevContextEntity>> = devContextRepository.contexts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
