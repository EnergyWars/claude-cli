package com.wafflehq.commander.ui.feedback

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.FeedbackEntry
import com.wafflehq.commander.data.api.FeedbackList
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.ManifestPath
import com.wafflehq.commander.data.api.MessageResponse
import com.wafflehq.commander.data.api.Ticket
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
class FeedbackListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(id: Int, text: String = "Text") = FeedbackEntry(
        id = id,
        text = text,
        section = null,
        context = null,
        path = "periodical",
        createdAt = "c",
        updatedAt = "c",
    )

    private fun viewModel(api: ClServerApi): FeedbackListViewModel {
        val viewModel = FeedbackListViewModel(api, SavedStateHandle(mapOf("pathName" to "periodical")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `loads feedback for the current path on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } returns FeedbackList(listOf(entry(1)))
        }

        val viewModel = viewModel(api)

        assertEquals(1, viewModel.uiState.value.feedback.size)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } throws ApiException(500, "Serverfehler.")
        }

        val viewModel = viewModel(api)

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.feedback.isEmpty())
    }

    @Test
    fun `startEdit and saveEdit update the entry via the API`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } returns FeedbackList(listOf(entry(1, "Alt")))
            coEvery { updateFeedback(1, "Neu") } returns entry(1, "Neu")
        }
        val viewModel = viewModel(api)

        viewModel.startEdit(viewModel.uiState.value.feedback.first())
        assertEquals(1, viewModel.uiState.value.editingId)
        viewModel.onEditTextChange("Neu")

        viewModel.saveEdit()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.editingId)
        assertEquals("Neu", viewModel.uiState.value.feedback.first().text)
    }

    @Test
    fun `cancelEdit clears the editing state without saving`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } returns FeedbackList(listOf(entry(1, "Alt")))
        }
        val viewModel = viewModel(api)

        viewModel.startEdit(viewModel.uiState.value.feedback.first())
        viewModel.onEditTextChange("Verworfen")
        viewModel.cancelEdit()

        assertNull(viewModel.uiState.value.editingId)
        assertEquals("", viewModel.uiState.value.editText)
        assertEquals("Alt", viewModel.uiState.value.feedback.first().text)
    }

    @Test
    fun `delete removes the entry from the list`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } returns FeedbackList(listOf(entry(1)))
            coEvery { deleteFeedback(1) } returns MessageResponse("geloescht")
        }
        val viewModel = viewModel(api)

        viewModel.delete(viewModel.uiState.value.feedback.first())
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.feedback.isEmpty())
    }

    @Test
    fun `startConvert preselects the current path if it is among the manifest paths`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } returns FeedbackList(listOf(entry(1)))
            coEvery { getManifest() } returns Manifest(
                agents = emptyList(),
                paths = listOf(
                    ManifestPath("other", emptyList(), emptyList()),
                    ManifestPath("periodical", emptyList(), emptyList()),
                ),
            )
        }
        val viewModel = viewModel(api)

        viewModel.startConvert(viewModel.uiState.value.feedback.first())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("other", "periodical"), viewModel.uiState.value.projectNames)
        assertEquals(1, viewModel.uiState.value.convertProjectIndex)
    }

    @Test
    fun `confirmConvert creates a ticket and removes the feedback entry`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getFeedback("periodical") } returns FeedbackList(listOf(entry(1, "Absturz")))
            coEvery { getManifest() } returns Manifest(
                agents = emptyList(),
                paths = listOf(ManifestPath("periodical", emptyList(), emptyList())),
            )
            coEvery { createTicket("periodical", "Absturz") } returns mockk<Ticket>(relaxed = true)
            coEvery { deleteFeedback(1) } returns MessageResponse("geloescht")
        }
        val viewModel = viewModel(api)
        viewModel.startConvert(viewModel.uiState.value.feedback.first())
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmConvert()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { api.createTicket("periodical", "Absturz") }
        coVerify { api.deleteFeedback(1) }
        assertTrue(viewModel.uiState.value.feedback.isEmpty())
        assertNull(viewModel.uiState.value.convertingEntry)
    }
}
