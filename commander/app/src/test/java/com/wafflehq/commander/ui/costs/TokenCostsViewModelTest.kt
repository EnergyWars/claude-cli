package com.wafflehq.commander.ui.costs

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CostOverview
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
class TokenCostsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): TokenCostsViewModel {
        val viewModel = TokenCostsViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private val overview = CostOverview(
        totalCostUsd = 12.34,
        totalInputTokens = 1_000,
        totalOutputTokens = 2_000,
        totalCacheCreationInputTokens = 500,
        totalCacheReadInputTokens = 300,
        projects = emptyList(),
    )

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getCosts() } returns overview
        }

        val viewModel = TokenCostsViewModel(api)

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `loads costs on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getCosts() } returns overview
        }

        val viewModel = viewModel(api)

        assertEquals(overview, viewModel.uiState.value.data)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getCosts() } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.data)
    }

    @Test
    fun `refresh reloads costs and clears a previous error`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getCosts() } coAnswers {
                callCount++
                if (callCount == 1) throw ApiException(500, "Serverfehler.") else overview
            }
        }
        val viewModel = viewModel(api)
        assertEquals("Serverfehler.", viewModel.uiState.value.error)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(overview, viewModel.uiState.value.data)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed refresh keeps the previously loaded data`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getCosts() } coAnswers {
                callCount++
                if (callCount == 1) overview else throw ApiException(500, "Serverfehler.")
            }
        }
        val viewModel = viewModel(api)
        assertEquals(overview, viewModel.uiState.value.data)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(overview, viewModel.uiState.value.data)
    }
}
