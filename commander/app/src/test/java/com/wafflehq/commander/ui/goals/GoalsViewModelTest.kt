package com.wafflehq.commander.ui.goals

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.GoalFile
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
class GoalsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun goal(name: String, content: String = "# $name") =
        GoalFile(name = name, content = content, timestamp = "2026-04-05T06:07:08.000Z")

    private fun viewModel(api: ClServerApi): GoalsViewModel {
        val viewModel = GoalsViewModel(api, SavedStateHandle(mapOf("pathName" to "myapp")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
        }
        val viewModel = GoalsViewModel(api, SavedStateHandle(mapOf("pathName" to "myapp")))

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `loads the goal files of the current path on init`() = runTest(dispatcher) {
        val goals = listOf(goal("alpha.md"), goal("beta.MD", content = ""))
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns goals
        }

        val viewModel = viewModel(api)

        assertEquals("myapp", viewModel.pathName)
        assertEquals(goals, viewModel.uiState.value.goals)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `an empty result leaves an empty list without an error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
        }

        val viewModel = viewModel(api)

        assertTrue(viewModel.uiState.value.goals.isEmpty())
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failed load reports the error and clears loading`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } throws ApiException(404, "Pfad unbekannt.")
        }

        val viewModel = viewModel(api)

        assertEquals("Pfad unbekannt.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.goals.isEmpty())
    }

    @Test
    fun `refresh reloads the goal files and clears a previous error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi>()
        coEvery { api.getGoals("myapp") } throws ApiException(500, "Serverfehler.")
        val viewModel = viewModel(api)
        assertEquals("Serverfehler.", viewModel.uiState.value.error)

        val goals = listOf(goal("alpha.md"))
        coEvery { api.getGoals("myapp") } returns goals
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(goals, viewModel.uiState.value.goals)
        assertNull(viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        coVerify(exactly = 2) { api.getGoals("myapp") }
    }
}
