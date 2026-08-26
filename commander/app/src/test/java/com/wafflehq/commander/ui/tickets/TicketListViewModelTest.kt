package com.wafflehq.commander.ui.tickets

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.TICKET_STATUS_GENERATING
import com.wafflehq.commander.data.api.Ticket
import com.wafflehq.commander.data.api.TicketList
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

@OptIn(ExperimentalCoroutinesApi::class)
class TicketListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ticket(id: Int, status: String): Ticket = Ticket(
        id = id,
        pathName = "myapp",
        originalRequest = "Original",
        summary = "Zusammenfassung",
        claudeInstruction = "Instruction",
        category = "Backend",
        status = status,
        ipAddress = null,
        createdAt = "c",
        updatedAt = "u",
    )

    private fun viewModel(api: ClServerApi): TicketListViewModel {
        val viewModel = TicketListViewModel(api, SavedStateHandle(mapOf("pathName" to "myapp")))
        dispatcher.scheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `refresh loads tickets for the given path`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } returns TicketList(listOf(ticket(1, "open")))
        }

        val viewModel = viewModel(api)

        assertEquals(1, viewModel.uiState.value.tickets.size)
        assertEquals(false, viewModel.uiState.value.loading)
    }

    @Test
    fun `a listTickets failure surfaces the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } throws ApiException(500, "Server-Fehler.")
        }

        val viewModel = viewModel(api)

        assertEquals(false, viewModel.uiState.value.loading)
        assertEquals("Server-Fehler.", viewModel.uiState.value.error)
    }

    @Test
    fun `a generating ticket triggers a refetch every second until it leaves the status`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } returnsMany listOf(
                TicketList(listOf(ticket(1, TICKET_STATUS_GENERATING))),
                TicketList(listOf(ticket(1, TICKET_STATUS_GENERATING))),
                TicketList(listOf(ticket(1, "open"))),
            )
        }

        val viewModel = viewModel(api)
        assertEquals(TICKET_STATUS_GENERATING, viewModel.uiState.value.tickets.first().status)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("open", viewModel.uiState.value.tickets.first().status)
        coVerify(exactly = 3) { api.listTickets("myapp", null) }
    }

    @Test
    fun `no generating tickets means no polling`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } returns TicketList(listOf(ticket(1, "open")))
        }

        val viewModel = viewModel(api)
        dispatcher.scheduler.advanceTimeBy(5_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.tickets.size)
        coVerify(exactly = 1) { api.listTickets("myapp", null) }
    }

    @Test
    fun `createTicket clears the input, calls the api and refreshes the list`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } returns TicketList(emptyList())
            coEvery { createTicket("myapp", "neues Feature") } returns ticket(2, TICKET_STATUS_GENERATING)
        }
        val viewModel = viewModel(api)

        viewModel.onCreateTextChange("neues Feature")
        viewModel.createTicket()
        dispatcher.scheduler.runCurrent()

        assertEquals("", viewModel.uiState.value.createText)
        coVerify { api.createTicket("myapp", "neues Feature") }
        coVerify(atLeast = 2) { api.listTickets("myapp", null) }
    }

    @Test
    fun `createTicket with blank text does not call the api`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } returns TicketList(emptyList())
        }
        val viewModel = viewModel(api)

        viewModel.onCreateTextChange("   ")
        viewModel.createTicket()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { api.createTicket(any(), any()) }
    }

    @Test
    fun `onStatusFilterSelected refetches with the selected status`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { listTickets("myapp", null) } returns TicketList(emptyList())
            coEvery { listTickets("myapp", "done") } returns TicketList(listOf(ticket(3, "done")))
        }
        val viewModel = viewModel(api)

        val doneIndex = TICKET_STATUS_FILTERS.indexOf("done")
        viewModel.onStatusFilterSelected(doneIndex)
        dispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.tickets.size)
        coVerify { api.listTickets("myapp", "done") }
    }
}
