package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.Ticket
import com.wafflehq.commander.data.api.TicketPatchRequest
import com.wafflehq.commander.data.usage.UsageRepository
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
import org.junit.Before
import org.junit.Test

private val EMPTY_MANIFEST = Manifest(agents = emptyList(), paths = emptyList())

private fun ticket(status: String = "open") = Ticket(
    id = 1,
    pathName = "myproject",
    originalRequest = "req",
    summary = "summary",
    claudeInstruction = "do the thing",
    category = "bug",
    status = status,
    createdAt = "now",
    updatedAt = "now",
)

@OptIn(ExperimentalCoroutinesApi::class)
class TicketDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedStateHandle() = SavedStateHandle(mapOf("pathName" to "myproject", "id" to 1))

    @Test
    fun `play triggers a usage refresh once the agent run was accepted`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getTicket("myproject", 1) } returns ticket()
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { runAgent(any(), "myproject", "do the thing", null) } returns CommandAccepted("cmd-1")
            coEvery { updateTicket("myproject", 1, any<TicketPatchRequest>()) } returns ticket(status = "done")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = TicketDetailViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.play()
        dispatcher.scheduler.runCurrent()

        assertEquals("cmd-1", viewModel.uiState.value.startedCommandId)
        coVerify(exactly = 1) { usageRepository.refresh() }
    }

    @Test
    fun `a failed play does not trigger a usage refresh`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getTicket("myproject", 1) } returns ticket()
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { runAgent(any(), "myproject", "do the thing", null) } throws ApiException(500, "Serverfehler.")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = TicketDetailViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.play()
        dispatcher.scheduler.runCurrent()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        coVerify(exactly = 0) { usageRepository.refresh() }
    }
}
