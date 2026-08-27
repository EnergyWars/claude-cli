package com.wafflehq.commander.ui.collect

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CollectResultEntry
import com.wafflehq.commander.data.api.CollectSummary
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): CollectViewModel =
        CollectViewModel(api, SavedStateHandle(mapOf("pathName" to "periodical")))

    @Test
    fun `starts without a summary or error`() {
        val api = mockk<ClServerApi>()
        val viewModel = viewModel(api)

        assertNull(viewModel.uiState.value.summary)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `collect calls the API for the current path and stores the summary`() = runTest(dispatcher) {
        val summary = CollectSummary(
            results = listOf(CollectResultEntry("periodical-debug", "periodical-debug.apk", "ok")),
            errors = emptyList(),
        )
        val api = mockk<ClServerApi> {
            coEvery { collect("periodical") } returns summary
        }
        val viewModel = viewModel(api)

        viewModel.collect()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(summary, viewModel.uiState.value.summary)
        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed collect reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { collect("periodical") } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = viewModel(api)

        viewModel.collect()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.summary)
    }
}
