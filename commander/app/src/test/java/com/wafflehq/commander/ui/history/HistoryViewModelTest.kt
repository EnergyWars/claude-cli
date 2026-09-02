package com.wafflehq.commander.ui.history

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandList
import com.wafflehq.commander.data.api.CommandState
import io.mockk.coAnswers
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val POLL_INTERVAL_MS = 3_000L

private fun command(id: String, status: String = "completed"): CommandState = CommandState(
    id = id,
    agent = "main",
    model = "sonnet",
    command = "cmd-$id",
    path = "/p",
    status = status,
    output = "",
    exitCode = 0,
    createdAt = id,
    updatedAt = id,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ClServerApi): HistoryViewModel {
        val viewModel = HistoryViewModel(api, SavedStateHandle(mapOf("pathName" to "myapp")))
        dispatcher.scheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `loads the first page of 5 on init`() = runTest(dispatcher) {
        val page = listOf(command("5"), command("4"), command("3"), command("2"), command("1"))
        val api = mockk<ClServerApi> {
            coEvery { getCommands("myapp", limit = HISTORY_PAGE_SIZE, offset = 0) } returns
                CommandList(page, total = 7, limit = 5, offset = 0, hasMore = true)
        }

        val viewModel = viewModel(api)

        assertEquals(page.map { it.id }, viewModel.uiState.value.commands.map { it.id })
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.hasMore)
    }

    @Test
    fun `loadMore appends the next page without duplicating already-loaded ids`() = runTest(dispatcher) {
        val firstPage = listOf(command("5"), command("4"), command("3"), command("2"), command("1"))
        val secondPage = listOf(command("0"))
        val api = mockk<ClServerApi> {
            coEvery { getCommands("myapp", limit = 5, offset = 0) } returns
                CommandList(firstPage, total = 6, limit = 5, offset = 0, hasMore = true)
            coEvery { getCommands("myapp", limit = 5, offset = 5) } returns
                CommandList(secondPage, total = 6, limit = 5, offset = 5, hasMore = false)
        }
        val viewModel = viewModel(api)

        viewModel.loadMore()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("5", "4", "3", "2", "1", "0"), viewModel.uiState.value.commands.map { it.id })
        assertFalse(viewModel.uiState.value.hasMore)
        assertFalse(viewModel.uiState.value.loadingMore)
    }

    @Test
    fun `loadMore does nothing once there is no more page left to load`() = runTest(dispatcher) {
        val firstPage = listOf(command("1"))
        val api = mockk<ClServerApi> {
            coEvery { getCommands("myapp", limit = 5, offset = 0) } returns
                CommandList(firstPage, total = 1, limit = 5, offset = 0, hasMore = false)
        }
        val viewModel = viewModel(api)

        viewModel.loadMore()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { api.getCommands("myapp", limit = 5, offset = 1) }
        assertEquals(listOf("1"), viewModel.uiState.value.commands.map { it.id })
    }

    @Test
    fun `a background poll refreshes the currently loaded window in place, keeping already loaded pages`() =
        runTest(dispatcher) {
            val firstPage = listOf(command("5"), command("4"), command("3"), command("2"), command("1"))
            val secondPage = listOf(command("0"))
            val api = mockk<ClServerApi> {
                coEvery { getCommands("myapp", limit = 5, offset = 0) } returns
                    CommandList(firstPage, total = 6, limit = 5, offset = 0, hasMore = true)
                coEvery { getCommands("myapp", limit = 5, offset = 5) } returns
                    CommandList(secondPage, total = 6, limit = 5, offset = 5, hasMore = false)
                coEvery { getCommands("myapp", limit = 6, offset = 0) } returns
                    CommandList(
                        (firstPage + secondPage).map { it.copy(status = "running") },
                        total = 6,
                        limit = 6,
                        offset = 0,
                        hasMore = false,
                    )
            }
            val viewModel = viewModel(api)
            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()
            assertEquals(6, viewModel.uiState.value.commands.size)

            dispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            dispatcher.scheduler.runCurrent()

            // Fenster bleibt bei 6 geladenen Eintraegen (kein Reset auf die erste 5er-Seite), nur der Inhalt wird aktualisiert.
            assertEquals(6, viewModel.uiState.value.commands.size)
            assertEquals(
                listOf("5", "4", "3", "2", "1", "0"),
                viewModel.uiState.value.commands.map { it.id },
            )
            assertTrue(viewModel.uiState.value.commands.all { it.status == "running" })
        }

    @Test
    fun `a failed background poll keeps the already loaded list and only sets the error`() = runTest(dispatcher) {
        val firstPage = listOf(command("1"))
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getCommands("myapp", limit = 5, offset = 0) } coAnswers {
                callCount += 1
                if (callCount == 1) {
                    CommandList(firstPage, total = 1, limit = 5, offset = 0, hasMore = false)
                } else {
                    throw ApiException(500, "Serverfehler.")
                }
            }
        }
        val viewModel = viewModel(api)
        assertEquals(listOf("1"), viewModel.uiState.value.commands.map { it.id })

        dispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("1"), viewModel.uiState.value.commands.map { it.id })
        assertEquals("Serverfehler.", viewModel.uiState.value.error)
    }
}
