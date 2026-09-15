package com.wafflehq.commander.data.usage

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.UsageLimit
import java.time.Instant
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

    private val _lastUpdatedAt = MutableStateFlow<Instant?>(null)
    val lastUpdatedAt: StateFlow<Instant?> = _lastUpdatedAt.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    suspend fun refresh() {
        _isRefreshing.value = true
        try {
            _usageLimits.value = api.getUsage()
            _lastUpdatedAt.value = Instant.now()
        } catch (_: ApiException) {
        } finally {
            _isRefreshing.value = false
        }
    }
}
