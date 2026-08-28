package com.wafflehq.commander.ui.settings.config

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.ConfigPutResponse
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val rawConfig = Json.parseToJsonElement("""{"agents":[],"paths":[]}""")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): ConfigViewModel {
        val viewModel = ConfigViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `loads the current config as pretty printed json on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfig() } returns rawConfig
        }

        val viewModel = viewModel(api)

        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.jsonInput.contains("\"agents\""))
    }

    @Test
    fun `a failed load reports the error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfig() } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `save rejects invalid json without calling the api`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfig() } returns rawConfig
        }
        val viewModel = viewModel(api)

        viewModel.onJsonChange("not json")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Ungültiges JSON.", viewModel.uiState.value.error)
        coVerify(exactly = 0) { api.putConfig(any()) }
    }

    @Test
    fun `save success updates state from the response`() = runTest(dispatcher) {
        val newConfig = Json.parseToJsonElement("""{"agents":[],"paths":[],"main":{}}""")
        val api = mockk<ClServerApi> {
            coEvery { getConfig() } returns rawConfig
            coEvery { putConfig(any()) } returns ConfigPutResponse(
                versionId = 2,
                createdAt = "2026-08-28T10:00:00.000Z",
                config = newConfig,
                warning = "databaseDirectory wurde geaendert.",
            )
        }
        val viewModel = viewModel(api)

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.saved)
        assertFalse(state.saving)
        assertEquals("databaseDirectory wurde geaendert.", state.warning)
        assertTrue(state.jsonInput.contains("\"main\""))
    }

    @Test
    fun `save failure reports the error and keeps the edited input`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfig() } returns rawConfig
            coEvery { putConfig(any()) } throws ApiException(400, "Ungueltige Config.")
        }
        val viewModel = viewModel(api)

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Ungueltige Config.", state.error)
        assertFalse(state.saving)
        assertFalse(state.saved)
    }

    @Test
    fun `changing the json clears a previous saved flag`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getConfig() } returns rawConfig
            coEvery { putConfig(any()) } returns ConfigPutResponse(1, "2026-08-28T10:00:00.000Z", rawConfig)
        }
        val viewModel = viewModel(api)
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saved)

        viewModel.onJsonChange("{}")

        assertFalse(viewModel.uiState.value.saved)
    }
}
