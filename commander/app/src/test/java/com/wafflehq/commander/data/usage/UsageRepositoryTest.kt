package com.wafflehq.commander.data.usage

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.UsageLimit
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageRepositoryTest {

    @Test
    fun `refresh populates usageLimits from the API`() = runTest {
        val limits = listOf(UsageLimit("Current session", 42, "resets soon"))
        val api = mockk<ClServerApi> { coEvery { getUsage() } returns limits }
        val repository = UsageRepository(api)

        repository.refresh()

        assertEquals(limits, repository.usageLimits.value)
    }

    @Test
    fun `refresh starts out empty before the first call`() {
        val api = mockk<ClServerApi>()
        val repository = UsageRepository(api)

        assertEquals(emptyList<UsageLimit>(), repository.usageLimits.value)
    }

    @Test
    fun `a failed refresh is silently ignored, keeps the previous state`() = runTest {
        val limits = listOf(UsageLimit("Current session", 42, "resets soon"))
        val api = mockk<ClServerApi> { coEvery { getUsage() } returns limits }
        val repository = UsageRepository(api)
        repository.refresh()

        coEvery { api.getUsage() } throws ApiException(500, "Serverfehler.")
        repository.refresh()

        assertEquals(limits, repository.usageLimits.value)
    }

    @Test
    fun `each call to refresh triggers a new fetch, so callers can force a refetch on demand`() = runTest {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getUsage() } coAnswers {
                callCount += 1
                listOf(UsageLimit("Current session", callCount * 10, "x"))
            }
        }
        val repository = UsageRepository(api)

        repository.refresh()
        assertEquals(10, repository.usageLimits.value.first().percentUsed)

        repository.refresh()
        assertEquals(20, repository.usageLimits.value.first().percentUsed)
    }

    @Test
    fun `lastUpdatedAt starts null and is set after a successful refresh`() = runTest {
        val api = mockk<ClServerApi> { coEvery { getUsage() } returns emptyList() }
        val repository = UsageRepository(api)

        assertEquals(null, repository.lastUpdatedAt.value)

        repository.refresh()

        assertEquals(true, repository.lastUpdatedAt.value != null)
    }

    @Test
    fun `lastUpdatedAt is not set after a failed refresh`() = runTest {
        val api = mockk<ClServerApi> { coEvery { getUsage() } throws ApiException(500, "Serverfehler.") }
        val repository = UsageRepository(api)

        repository.refresh()

        assertEquals(null, repository.lastUpdatedAt.value)
    }

    @Test
    fun `isRefreshing is false before and after refresh completes`() = runTest {
        val api = mockk<ClServerApi> { coEvery { getUsage() } returns emptyList() }
        val repository = UsageRepository(api)

        assertEquals(false, repository.isRefreshing.value)

        repository.refresh()

        assertEquals(false, repository.isRefreshing.value)
    }
}
