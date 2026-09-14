package com.wafflehq.commander.ui.metrics

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SystemMetricSample
import com.wafflehq.commander.data.api.SystemMetricsResponse
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
class SystemMetricsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): SystemMetricsViewModel {
        val viewModel = SystemMetricsViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private val response = SystemMetricsResponse(
        metrics = listOf(
            SystemMetricSample(
                createdAt = "2026-09-14T12:00:00.000Z",
                cpuPercent = 23.4,
                memUsedPercent = 61.2,
                memTotalBytes = 16_000_000_000L,
                memFreeBytes = 6_200_000_000L,
            ),
        ),
        windowHours = 24.0,
    )

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getSystemMetrics() } returns response
        }

        val viewModel = SystemMetricsViewModel(api)

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `loads metrics on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getSystemMetrics() } returns response
        }

        val viewModel = viewModel(api)

        assertEquals(response, viewModel.uiState.value.data)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `an empty metrics list is not treated as an error`() = runTest(dispatcher) {
        val empty = SystemMetricsResponse(metrics = emptyList(), windowHours = 24.0)
        val api = mockk<ClServerApi> {
            coEvery { getSystemMetrics() } returns empty
        }

        val viewModel = viewModel(api)

        assertEquals(empty, viewModel.uiState.value.data)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getSystemMetrics() } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.data)
    }

    @Test
    fun `refresh reloads metrics and clears a previous error`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getSystemMetrics() } coAnswers {
                callCount++
                if (callCount == 1) throw ApiException(500, "Serverfehler.") else response
            }
        }
        val viewModel = viewModel(api)
        assertEquals("Serverfehler.", viewModel.uiState.value.error)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(response, viewModel.uiState.value.data)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed refresh keeps the previously loaded data`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getSystemMetrics() } coAnswers {
                callCount++
                if (callCount == 1) response else throw ApiException(500, "Serverfehler.")
            }
        }
        val viewModel = viewModel(api)
        assertEquals(response, viewModel.uiState.value.data)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(response, viewModel.uiState.value.data)
    }
}
