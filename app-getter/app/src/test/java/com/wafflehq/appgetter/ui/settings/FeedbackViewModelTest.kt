package com.wafflehq.appgetter.ui.settings

import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.api.FeedbackEntry
import com.wafflehq.appgetter.data.discovery.NetworkDiscovery
import com.wafflehq.appgetter.data.settings.ServerOverride
import com.wafflehq.appgetter.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
class FeedbackViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun settingsRepository(host: String? = "192.168.1.5", port: Int = 8787): SettingsRepository =
        mockk<SettingsRepository> {
            every { serverOverride } returns flowOf(ServerOverride(host, port))
        }

    @Test
    fun `blank text does not trigger a request`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi>()
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.onTextChange("   ")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.sending)
        assertEquals(false, viewModel.uiState.value.sent)
    }

    @Test
    fun `successful send clears the text and marks it sent`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback("192.168.1.5", 8787, "Tolle App") } returns
                FeedbackEntry(1, "Tolle App", "2026-08-26T00:00:00.000Z", "2026-08-26T00:00:00.000Z")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.sent)
        assertEquals("", state.text)
        assertEquals(false, state.sending)
        assertNull(state.error)
    }

    @Test
    fun `failed send keeps the text and reports the error`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback(any(), any(), any()) } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(false, state.sent)
        assertEquals("Tolle App", state.text)
        assertEquals("Serverfehler.", state.error)
    }

    @Test
    fun `no host available reports an error without calling the api`() = runTest(dispatcher) {
        val discovery = mockk<NetworkDiscovery> {
            coEvery { discoverHost(8787) } returns null
        }
        val api = mockk<AppGetterApi>()
        val viewModel = FeedbackViewModel(settingsRepository(host = null), discovery, api)

        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.sending)
        assertEquals(false, viewModel.uiState.value.sent)
        assertTrue(viewModel.uiState.value.error != null)
    }
}
