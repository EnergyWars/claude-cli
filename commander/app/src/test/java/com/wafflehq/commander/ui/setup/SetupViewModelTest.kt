package com.wafflehq.commander.ui.setup

import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.HealthResponse
import com.wafflehq.commander.data.connection.ConnectionRepository
import com.wafflehq.commander.data.discovery.NetworkDiscovery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<ClServerApi>()
    private val discovery = mockk<NetworkDiscovery>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(history: List<String> = emptyList()): ConnectionRepository = mockk(relaxed = true) {
        every { hostHistory } returns MutableStateFlow(history)
    }

    private fun viewModel(repository: ConnectionRepository = repository()) =
        SetupViewModel(api, repository, discovery)

    @Test
    fun `defaults to the test port`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(PortOption.Test, model.portOption.value)
    }

    @Test
    fun `connects with the fixed service port`() = runTest(dispatcher) {
        val repository = repository()
        coEvery { api.health("192.168.0.10", SERVICE_PORT) } returns HealthResponse("ok", "1.0.0")
        val model = viewModel(repository)

        model.onHostChange("192.168.0.10")
        model.onPortOptionChange(PortOption.Service)
        model.connect()
        advanceUntilIdle()

        assertEquals(SetupStatus.Connected, model.status.value)
        coVerify { repository.saveConnection("192.168.0.10", SERVICE_PORT) }
    }

    @Test
    fun `connects with a custom port`() = runTest(dispatcher) {
        val repository = repository()
        coEvery { api.health("192.168.0.10", 9000) } returns HealthResponse("ok", "1.0.0")
        val model = viewModel(repository)

        model.onHostChange("192.168.0.10")
        model.onPortOptionChange(PortOption.Custom)
        model.onCustomPortChange("9000")
        model.connect()
        advanceUntilIdle()

        assertEquals(SetupStatus.Connected, model.status.value)
        coVerify { repository.saveConnection("192.168.0.10", 9000) }
    }

    @Test
    fun `custom port input keeps digits only and caps at five characters`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onCustomPortChange("9a0b0c0123")

        assertEquals("90001", model.customPort.value)
    }

    @Test
    fun `rejects a missing custom port`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onHostChange("192.168.0.10")
        model.onPortOptionChange(PortOption.Custom)
        model.connect()
        advanceUntilIdle()

        assertEquals(
            SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_port_invalid)),
            model.status.value,
        )
    }

    @Test
    fun `rejects a missing host`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.connect()
        advanceUntilIdle()

        assertEquals(
            SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_host_missing)),
            model.status.value,
        )
    }

    @Test
    fun `reports the api error message`() = runTest(dispatcher) {
        coEvery { api.health(any(), any()) } throws ApiException(500, "Server kaputt")
        val model = viewModel()

        model.onHostChange("192.168.0.10")
        model.connect()
        advanceUntilIdle()

        assertEquals(SetupStatus.Error(SetupErrorMessage.Text("Server kaputt")), model.status.value)
        model.dismissError()
        assertEquals(SetupStatus.Idle, model.status.value)
    }

    @Test
    fun `preselects the most recent remembered host`() = runTest(dispatcher) {
        val model = viewModel(repository(listOf("192.168.0.11", "192.168.0.10")))
        advanceUntilIdle()

        assertEquals("192.168.0.11", model.host.value)
        assertEquals(listOf("192.168.0.11", "192.168.0.10"), model.hostHistory.value)
        assertFalse(model.hostInputVisible.value)
    }

    @Test
    fun `shows the host input when nothing is remembered`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        assertEquals("", model.host.value)
        assertTrue(model.hostInputVisible.value)
    }

    @Test
    fun `selecting a remembered host hides the input again`() = runTest(dispatcher) {
        val model = viewModel(repository(listOf("192.168.0.11", "192.168.0.10")))
        advanceUntilIdle()

        model.onNewHostSelected()
        advanceUntilIdle()
        assertTrue(model.hostInputVisible.value)
        assertEquals("", model.host.value)

        model.onHostSelected("192.168.0.10")
        advanceUntilIdle()
        assertFalse(model.hostInputVisible.value)
        assertEquals("192.168.0.10", model.host.value)
    }

    @Test
    fun `discovery fills the host input`() = runTest(dispatcher) {
        coEvery { discovery.discoverHost(TEST_PORT) } returns "192.168.0.42"
        val model = viewModel(repository(listOf("192.168.0.11")))
        advanceUntilIdle()

        model.discover()
        advanceUntilIdle()

        assertEquals("192.168.0.42", model.host.value)
        assertTrue(model.hostInputVisible.value)
        assertEquals(SetupStatus.Idle, model.status.value)
    }

    @Test
    fun `discovery reports when no server answers`() = runTest(dispatcher) {
        coEvery { discovery.discoverHost(SERVICE_PORT) } returns null
        val model = viewModel()
        advanceUntilIdle()

        model.onPortOptionChange(PortOption.Service)
        model.discover()
        advanceUntilIdle()

        assertEquals(
            SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_not_found)),
            model.status.value,
        )
    }

    @Test
    fun `discovery rejects an invalid custom port`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onPortOptionChange(PortOption.Custom)
        model.discover()
        advanceUntilIdle()

        assertEquals(
            SetupStatus.Error(SetupErrorMessage.Resource(R.string.setup_error_port_invalid)),
            model.status.value,
        )
    }
}
