package com.wafflehq.commander.data.usage

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.UsageLimit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class UsageRepository @Inject constructor(
    private val api: ClServerApi,
) {
    private val _usageLimits = MutableStateFlow<List<UsageLimit>>(emptyList())
    val usageLimits: StateFlow<List<UsageLimit>> = _usageLimits.asStateFlow()

    suspend fun refresh() {
        try {
            _usageLimits.value = api.getUsage()
        } catch (_: ApiException) {
        }
    }
}
