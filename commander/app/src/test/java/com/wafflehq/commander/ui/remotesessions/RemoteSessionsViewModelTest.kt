package com.wafflehq.commander.ui.remotesessions

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.RemoteAgentSession
import com.wafflehq.commander.data.api.RemoteSessionStart
import io.mockk.coEvery
import io.mockk.coVerify
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
class RemoteSessionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session(id: String? = null, kind: String = "interactive") = RemoteAgentSession(
        pid = 123,
        id = id,
        cwd = "/home/user/myapp",
        kind = kind,
        startedAt = 1_700_000_000_000,
        sessionId = "a1b2c3",
        name = "myapp-a1",
        status = "idle",
    )

    private fun viewModel(api: ClServerApi): RemoteSessionsViewModel {
        val viewModel = RemoteSessionsViewModel(api, SavedStateHandle(mapOf("pathName" to "myapp")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } returns emptyList()
        }
        val viewModel = RemoteSessionsViewModel(api, SavedStateHandle(mapOf("pathName" to "myapp")))

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `loads sessions for the current path on init`() = runTest(dispatcher) {
        val sessions = listOf(session(), session(id = "1771997d", kind = "background"))
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } returns sessions
        }

        val viewModel = viewModel(api)

        assertEquals(sessions, viewModel.uiState.value.sessions)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
    }

    @Test
    fun `startSession starts a session without a name, clears the input and refreshes`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } returns emptyList()
            coEvery { startRemoteSession("myapp", null) } returns RemoteSessionStart("abc123f9", "backgrounded")
        }
        val viewModel = viewModel(api)

        viewModel.startSession()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("abc123f9", viewModel.uiState.value.lastStartedId)
        assertEquals("", viewModel.uiState.value.nameInput)
        assertEquals(false, viewModel.uiState.value.starting)
        coVerify(exactly = 2) { api.getRemoteSessions("myapp") }
    }

    @Test
    fun `startSession trims and forwards a given name`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } returns emptyList()
            coEvery { startRemoteSession("myapp", "mein-name") } returns RemoteSessionStart("xyz98765", "backgrounded")
        }
        val viewModel = viewModel(api)
        viewModel.onNameInputChange("  mein-name  ")

        viewModel.startSession()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { api.startRemoteSession("myapp", "mein-name") }
    }

    @Test
    fun `startSession sends null for a blank name`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } returns emptyList()
            coEvery { startRemoteSession("myapp", null) } returns RemoteSessionStart("abc123f9", "backgrounded")
        }
        val viewModel = viewModel(api)
        viewModel.onNameInputChange("   ")

        viewModel.startSession()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { api.startRemoteSession("myapp", null) }
    }

    @Test
    fun `a failed start reports the error and clears the starting flag`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getRemoteSessions("myapp") } returns emptyList()
            coEvery { startRemoteSession("myapp", null) } throws ApiException(500, "Spawn fehlgeschlagen.")
        }
        val viewModel = viewModel(api)

        viewModel.startSession()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Spawn fehlgeschlagen.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.starting)
        assertNull(viewModel.uiState.value.lastStartedId)
    }
}
