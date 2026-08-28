package com.wafflehq.commander.ui.settings.config

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ConfigPointerResponse
import com.wafflehq.commander.data.api.ConfigPointerUpdateResponse
import com.wafflehq.commander.data.api.ConfigVersionSummary
import com.wafflehq.commander.data.api.ConfigVersionsResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigVersionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val versions = listOf(
        ConfigVersionSummary(2, "2026-08-28T10:00:00.000Z"),
        ConfigVersionSummary(1, "2026-08-01T08:00:00.000Z"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): ConfigVersionsViewModel {
        val viewModel = ConfigVersionsViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `loads versions and the active pointer on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfigVersions() } returns ConfigVersionsResponse(versions)
            coEvery { getConfigPointer() } returns ConfigPointerResponse(2)
        }

        val viewModel = viewModel(api)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(versions, state.versions)
        assertEquals(2, state.activeVersionId)
    }

    @Test
    fun `a null pointer means the embedded version is active`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfigVersions() } returns ConfigVersionsResponse(versions)
            coEvery { getConfigPointer() } returns ConfigPointerResponse(null)
        }

        val viewModel = viewModel(api)

        assertNull(viewModel.uiState.value.activeVersionId)
    }

    @Test
    fun `a failed load reports the error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfigVersions() } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `activating a version updates the active pointer`() = runTest(dispatcher) {
        val newConfig = Json.parseToJsonElement("{}")
        val api = mockk<ClServerApi> {
            coEvery { getConfigVersions() } returns ConfigVersionsResponse(versions)
            coEvery { getConfigPointer() } returns ConfigPointerResponse(2)
            coEvery { setConfigPointer(1) } returns ConfigPointerUpdateResponse(1, newConfig)
        }
        val viewModel = viewModel(api)

        viewModel.activate(1)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.activeVersionId)
        assertFalse(state.switching)
    }

    @Test
    fun `activating embedded surfaces the databaseDirectory restart warning`() = runTest(dispatcher) {
        val newConfig = Json.parseToJsonElement("{}")
        val api = mockk<ClServerApi> {
            coEvery { getConfigVersions() } returns ConfigVersionsResponse(versions)
            coEvery { getConfigPointer() } returns ConfigPointerResponse(2)
            coEvery { setConfigPointer(null) } returns ConfigPointerUpdateResponse(
                versionId = null,
                config = newConfig,
                warning = "databaseDirectory wurde geaendert.",
            )
        }
        val viewModel = viewModel(api)

        viewModel.activate(null)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.activeVersionId)
        assertEquals("databaseDirectory wurde geaendert.", state.warning)
    }

    @Test
    fun `a failed activation reports the error and keeps the previous pointer`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfigVersions() } returns ConfigVersionsResponse(versions)
            coEvery { getConfigPointer() } returns ConfigPointerResponse(2)
            coEvery { setConfigPointer(1) } throws ApiException(404, "Config-Version 1 wurde nicht gefunden.")
        }
        val viewModel = viewModel(api)

        viewModel.activate(1)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.activeVersionId)
        assertEquals("Config-Version 1 wurde nicht gefunden.", state.error)
        assertFalse(state.switching)
    }
}
