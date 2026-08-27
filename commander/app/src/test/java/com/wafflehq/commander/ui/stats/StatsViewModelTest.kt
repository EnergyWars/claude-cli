package com.wafflehq.commander.ui.stats

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ProjectStats
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): StatsViewModel {
        val viewModel = StatsViewModel(api, SavedStateHandle(mapOf("pathName" to "periodical")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getStats("periodical") } returns ProjectStats(0, 0, 24.0, null, null)
        }
        val viewModel = StatsViewModel(api, SavedStateHandle(mapOf("pathName" to "periodical")))

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `loads stats for the current path on init`() = runTest(dispatcher) {
        val stats = ProjectStats(
            runningAgents = 2,
            agentsInWindow = 5,
            windowHours = 24.0,
            lastDebugBuildAt = "2026-02-01T08:00:00.000Z",
            lastReleaseBuildAt = null,
        )
        val api = mockk<ClServerApi> {
            coEvery { getStats("periodical") } returns stats
        }

        val viewModel = viewModel(api)

        assertEquals(stats, viewModel.uiState.value.stats)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getStats("periodical") } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.stats)
    }

    @Test
    fun `refresh reloads stats and clears a previous error`() = runTest(dispatcher) {
        val stats = ProjectStats(1, 1, 24.0, null, null)
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getStats("periodical") } coAnswers {
                callCount++
                if (callCount == 1) throw ApiException(500, "Serverfehler.") else stats
            }
        }
        val viewModel = viewModel(api)
        assertEquals("Serverfehler.", viewModel.uiState.value.error)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(stats, viewModel.uiState.value.stats)
        assertNull(viewModel.uiState.value.error)
    }
}
