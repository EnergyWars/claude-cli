package com.wafflehq.commander.ui.navigation

import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.connection.AuthSession
import com.wafflehq.commander.data.connection.Connection
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import com.wafflehq.commander.data.settings.SettingsRepository
import com.wafflehq.commander.data.usage.UsageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeConnectionSource(initial: Session?) : ConnectionSource {
    override val session = MutableStateFlow(initial)
}

private fun fakeSettingsRepository(selectedProjectName: String? = null): SettingsRepository =
    mockk<SettingsRepository> {
        every { this@mockk.selectedProjectName } returns flowOf(selectedProjectName)
    }

private fun fakeClServerApi(): ClServerApi = mockk<ClServerApi> {
    coEvery { refreshSessionIfLoggedIn() } returns Unit
}

private fun fakeUsageRepository(): UsageRepository = mockk<UsageRepository> {
    coEvery { refresh() } returns Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionGateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no stored connection maps to NoConnection`() = runTest(dispatcher) {
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(null), fakeSettingsRepository(), fakeClServerApi(), fakeUsageRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(GateState.NoConnection, viewModel.gateState.value)
    }

    @Test
    fun `connection without an auth session maps to NeedsLogin`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), auth = null)
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(), fakeClServerApi(), fakeUsageRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(GateState.NeedsLogin, viewModel.gateState.value)
    }

    @Test
    fun `connection with a valid token but no selected project maps to Ready without a project`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), AuthSession("token", Instant.now().plusSeconds(3600)))
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(selectedProjectName = null), fakeClServerApi(), fakeUsageRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(GateState.Ready(hasSelectedProject = false), viewModel.gateState.value)
    }

    @Test
    fun `connection with a valid token and a remembered project maps to Ready with a project`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), AuthSession("token", Instant.now().plusSeconds(3600)))
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(selectedProjectName = "periodical"), fakeClServerApi(), fakeUsageRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(GateState.Ready(hasSelectedProject = true), viewModel.gateState.value)
    }

    @Test
    fun `connection with an already-expired token maps to NeedsLogin`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), AuthSession("token", Instant.now().minusSeconds(1)))
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(), fakeClServerApi(), fakeUsageRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(GateState.NeedsLogin, viewModel.gateState.value)
    }

    @Test
    fun `state flips to NeedsLogin on its own once the token expires, without a new emission`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), AuthSession("token", Instant.now().plusSeconds(5)))
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(), fakeClServerApi(), fakeUsageRepository())
        dispatcher.scheduler.runCurrent()
        assertEquals(GateState.Ready(hasSelectedProject = false), viewModel.gateState.value)

        dispatcher.scheduler.advanceTimeBy(6_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(GateState.NeedsLogin, viewModel.gateState.value)
    }

    @Test
    fun `refreshTokenOnForeground delegates to ClServerApi`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), AuthSession("token", Instant.now().plusSeconds(3600)))
        val clServerApi = fakeClServerApi()
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(), clServerApi, fakeUsageRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.refreshTokenOnForeground()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { clServerApi.refreshSessionIfLoggedIn() }
    }

    @Test
    fun `refreshUsage delegates to UsageRepository`() = runTest(dispatcher) {
        val session = Session(Connection("host", 1), AuthSession("token", Instant.now().plusSeconds(3600)))
        val usageRepository = fakeUsageRepository()
        val viewModel = ConnectionGateViewModel(FakeConnectionSource(session), fakeSettingsRepository(), fakeClServerApi(), usageRepository)
        dispatcher.scheduler.runCurrent()

        viewModel.refreshUsage()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { usageRepository.refresh() }
    }
}
