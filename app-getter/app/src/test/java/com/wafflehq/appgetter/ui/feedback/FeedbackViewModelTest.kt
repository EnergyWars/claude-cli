package com.wafflehq.appgetter.ui.feedback

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
    fun `open sets the section and resets any leftover text`() {
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), mockk())

        viewModel.open("periodical-debug")

        val state = viewModel.uiState.value
        assertEquals("periodical-debug", state.openSection)
        assertEquals("", state.text)
        assertEquals("", state.context)
    }

    @Test
    fun `open prefills the context`() {
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), mockk())

        viewModel.open("periodical-debug", "periodical-debug.apk (2026-08-26T10:00:00.000Z)")

        assertEquals("periodical-debug.apk (2026-08-26T10:00:00.000Z)", viewModel.uiState.value.context)
    }

    @Test
    fun `dismiss closes the dialog and clears the text`() {
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), mockk())

        viewModel.open("periodical-debug", "Kontext")
        viewModel.onTextChange("Draft")
        viewModel.dismiss()

        val state = viewModel.uiState.value
        assertNull(state.openSection)
        assertEquals("", state.text)
        assertEquals("", state.context)
    }

    @Test
    fun `blank text does not trigger a request`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi>()
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.open("periodical-debug")
        viewModel.onTextChange("   ")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        assertEquals("periodical-debug", viewModel.uiState.value.openSection)
    }

    @Test
    fun `send clears the field and closes the dialog immediately`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback("192.168.1.5", 8787, "Tolle App", "periodical-debug", null) } returns
                FeedbackEntry(1, "Tolle App", "periodical-debug", null, "2026-08-26T00:00:00.000Z", "2026-08-26T00:00:00.000Z")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.open("periodical-debug")
        viewModel.onTextChange("Tolle App")
        viewModel.send()

        val state = viewModel.uiState.value
        assertNull(state.openSection)
        assertEquals("", state.text)
        assertNull(state.error)
    }

    @Test
    fun `send includes the section automatically`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback(any(), any(), any(), any(), any()) } returns
                FeedbackEntry(1, "Tolle App", "periodical-debug", null, "2026-08-26T00:00:00.000Z", "2026-08-26T00:00:00.000Z")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.open("periodical-debug")
        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        io.mockk.coVerify { api.sendFeedback("192.168.1.5", 8787, "Tolle App", "periodical-debug", null) }
    }

    @Test
    fun `send includes the prefilled context unchanged since it cannot be edited`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback(any(), any(), any(), any(), any()) } returns
                FeedbackEntry(1, "Tolle App", "periodical-debug", "Vorbelegt", "2026-08-26T00:00:00.000Z", "2026-08-26T00:00:00.000Z")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.open("periodical-debug", "  Vorbelegt  ")
        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        io.mockk.coVerify { api.sendFeedback("192.168.1.5", 8787, "Tolle App", "periodical-debug", "Vorbelegt") }
    }

    @Test
    fun `send drops a blank context`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback(any(), any(), any(), any(), any()) } returns
                FeedbackEntry(1, "Tolle App", "periodical-debug", null, "2026-08-26T00:00:00.000Z", "2026-08-26T00:00:00.000Z")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.open("periodical-debug")
        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        io.mockk.coVerify { api.sendFeedback("192.168.1.5", 8787, "Tolle App", "periodical-debug", null) }
    }

    @Test
    fun `failed background send reports the error without reopening the dialog`() = runTest(dispatcher) {
        val api = mockk<AppGetterApi> {
            coEvery { sendFeedback(any(), any(), any(), any(), any()) } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = FeedbackViewModel(settingsRepository(), mockk(), api)

        viewModel.open("periodical-debug")
        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.openSection)
        assertEquals("Serverfehler.", state.error)
    }

    @Test
    fun `no host available reports an error without calling the api`() = runTest(dispatcher) {
        val discovery = mockk<NetworkDiscovery> {
            coEvery { discoverHost(8787) } returns null
        }
        val api = mockk<AppGetterApi>()
        val viewModel = FeedbackViewModel(settingsRepository(host = null), discovery, api)

        viewModel.open("periodical-debug")
        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.error != null)
    }

    @Test
    fun `clearError resets the error state`() = runTest(dispatcher) {
        val discovery = mockk<NetworkDiscovery> {
            coEvery { discoverHost(8787) } returns null
        }
        val viewModel = FeedbackViewModel(settingsRepository(host = null), discovery, mockk())

        viewModel.open("periodical-debug")
        viewModel.onTextChange("Tolle App")
        viewModel.send()
        dispatcher.scheduler.runCurrent()
        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }
}
