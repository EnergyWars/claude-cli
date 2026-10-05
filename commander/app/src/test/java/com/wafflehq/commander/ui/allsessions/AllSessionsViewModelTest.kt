package com.wafflehq.commander.ui.allsessions

import com.wafflehq.commander.data.api.ActiveSession
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.KillSessionResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AllSessionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session(
        sessionId: String,
        activity: String,
        startedAt: Long = 1_700_000_000_000,
        name: String = "session-$sessionId",
    ) = ActiveSession(
        pid = 123,
        cwd = "/home/user/app",
        kind = "interactive",
        startedAt = startedAt,
        sessionId = sessionId,
        name = name,
        activity = activity,
    )

    private fun viewModel(api: ClServerApi): AllSessionsViewModel {
        val viewModel = AllSessionsViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> { coEvery { getAllSessions() } returns emptyList() }

        assertTrue(AllSessionsViewModel(api).uiState.value.loading)
    }

    @Test
    fun `loads and sorts sessions waiting first then working then idle, newest first within a group`() =
        runTest(dispatcher) {
            val idle = session("i", "idle")
            val working = session("w", "working")
            val waitingOld = session("wo", "waiting", startedAt = 1)
            val waitingNew = session("wn", "waiting", startedAt = 2)
            val api = mockk<ClServerApi> {
                coEvery { getAllSessions() } returns listOf(idle, working, waitingOld, waitingNew)
            }

            val state = viewModel(api).uiState.value

            assertEquals(listOf("wn", "wo", "w", "i"), state.sessions.map { it.sessionId })
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun `counts sessions per activity`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSessions() } returns listOf(
                session("a", "working"),
                session("b", "working"),
                session("c", "waiting"),
                session("d", "idle"),
                session("e", "unknown"),
            )
        }

        val state = viewModel(api).uiState.value

        assertEquals(2, state.workingCount)
        assertEquals(1, state.waitingCount)
        assertEquals(2, state.idleCount)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> { coEvery { getAllSessions() } throws ApiException(500, "Serverfehler.") }

        val state = viewModel(api).uiState.value

        assertEquals("Serverfehler.", state.error)
        assertFalse(state.loading)
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun `refresh reloads and clears a previous error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSessions() } throws ApiException(500, "Serverfehler.") andThen listOf(session("a", "idle"))
        }
        val viewModel = viewModel(api)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(1, viewModel.uiState.value.sessions.size)
    }

    @Test
    fun `polling reloads the sessions periodically`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> { coEvery { getAllSessions() } returns emptyList() }
        val viewModel = viewModel(api)

        val job = launch { viewModel.pollSessions() }
        dispatcher.scheduler.advanceTimeBy(3_000)
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(3_000)
        dispatcher.scheduler.runCurrent()
        job.cancel()

        coVerify(exactly = 3) { api.getAllSessions() }
    }

    @Test
    fun `a failed poll keeps the current sessions and does not report an error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSessions() } returns listOf(session("a", "idle")) andThenThrows ApiException(500, "x")
        }
        val viewModel = viewModel(api)

        val job = launch { viewModel.pollSessions() }
        dispatcher.scheduler.advanceTimeBy(3_000)
        dispatcher.scheduler.runCurrent()
        job.cancel()

        assertEquals(1, viewModel.uiState.value.sessions.size)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `kill terminates the session and reloads the list`() = runTest(dispatcher) {
        val target = session("a", "working")
        val api = mockk<ClServerApi> {
            coEvery { getAllSessions() } returns listOf(target) andThen emptyList()
            coEvery { killSession("a") } returns KillSessionResponse(true)
        }
        val viewModel = viewModel(api)

        viewModel.kill(target)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { api.killSession("a") }
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
        assertNull(viewModel.uiState.value.killingSessionId)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed kill reports the error and clears the killing marker`() = runTest(dispatcher) {
        val target = session("a", "working")
        val api = mockk<ClServerApi> {
            coEvery { getAllSessions() } returns listOf(target)
            coEvery { killSession("a") } throws ApiException(500, "Beenden fehlgeschlagen.")
        }
        val viewModel = viewModel(api)

        viewModel.kill(target)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Beenden fehlgeschlagen.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.killingSessionId)
        assertEquals(1, viewModel.uiState.value.sessions.size)
    }

    @Test
    fun `a second kill is ignored while one is running`() = runTest(dispatcher) {
        val first = session("a", "working")
        val second = session("b", "working")
        val gate = CompletableDeferred<KillSessionResponse>()
        val api = mockk<ClServerApi> {
            coEvery { getAllSessions() } returns listOf(first, second)
            coEvery { killSession("a") } coAnswers { gate.await() }
            coEvery { killSession("b") } returns KillSessionResponse(true)
        }
        val viewModel = viewModel(api)

        viewModel.kill(first)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("a", viewModel.uiState.value.killingSessionId)
        viewModel.kill(second)
        dispatcher.scheduler.advanceUntilIdle()
        gate.complete(KillSessionResponse(true))
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { api.killSession("b") }
    }

    @Test
    fun `sortSessions puts waiting before working before idle`() {
        val sorted = sortSessions(listOf(session("i", "idle"), session("w", "working"), session("x", "waiting")))

        assertEquals(listOf("x", "w", "i"), sorted.map { it.sessionId })
    }
}
