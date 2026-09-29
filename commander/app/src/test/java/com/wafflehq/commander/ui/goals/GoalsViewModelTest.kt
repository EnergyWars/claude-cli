package com.wafflehq.commander.ui.goals

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.GoalEntry
import com.wafflehq.commander.data.api.GoalListGroup
import com.wafflehq.commander.data.api.RemoteSessionStart
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun goal(
    id: String,
    fileName: String,
    dependsOn: List<String> = emptyList(),
    status: String = "ready",
    missingDependencies: List<String> = emptyList(),
    running: Boolean = false,
) = GoalEntry(
    id = id,
    fileName = fileName,
    title = "Titel $id",
    description = "Beschreibung $id",
    date = "2026-09-29",
    dependsOn = dependsOn,
    command = "/goal Tu etwas fuer $id.",
    content = "---\nid: $id\n---\n",
    timestamp = "2026-09-29T06:07:08.000Z",
    legacy = false,
    status = status,
    missingDependencies = missingDependencies,
    running = running,
)

private fun group(folder: String, vararg goals: GoalEntry) =
    GoalListGroup(folder = folder, planTitle = null, planDate = null, goals = goals.toList())

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

    private fun fakeUsageRepository(): UsageRepository = mockk { coEvery { refresh() } returns Unit }

    private fun viewModel(api: ClServerApi, usageRepository: UsageRepository = fakeUsageRepository()): GoalsViewModel {
        val viewModel = GoalsViewModel(api, usageRepository, SavedStateHandle(mapOf("pathName" to "myapp")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `starts in a loading state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
        }
        val viewModel = GoalsViewModel(api, fakeUsageRepository(), SavedStateHandle(mapOf("pathName" to "myapp")))

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `loads the goal groups of the current path on init`() = runTest(dispatcher) {
        val goals = listOf(group("2026-09-29-feature", goal("G01", "G01-erstes.md")))
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns goals
        }

        val viewModel = viewModel(api)

        assertEquals("myapp", viewModel.pathName)
        assertEquals(goals, viewModel.uiState.value.goalGroups)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `an empty result leaves an empty list without an error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
        }

        val viewModel = viewModel(api)

        assertTrue(viewModel.uiState.value.goalGroups.isEmpty())
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
        assertTrue(viewModel.uiState.value.goalGroups.isEmpty())
    }

    @Test
    fun `refresh reloads the goal groups and clears a previous error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi>()
        coEvery { api.getGoals("myapp") } throws ApiException(500, "Serverfehler.")
        val viewModel = viewModel(api)
        assertEquals("Serverfehler.", viewModel.uiState.value.error)

        val goals = listOf(group("2026-09-29-feature", goal("G01", "G01-erstes.md")))
        coEvery { api.getGoals("myapp") } returns goals
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(goals, viewModel.uiState.value.goalGroups)
        assertNull(viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
        coVerify(exactly = 2) { api.getGoals("myapp") }
    }

    @Test
    fun `startGoal on success stores the started command id and refreshes usage`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoal("myapp", "2026-09-29-feature", "G01-erstes.md") } returns CommandAccepted("cmd-1")
        }
        val usageRepository = fakeUsageRepository()
        val viewModel = viewModel(api, usageRepository)

        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("cmd-1", viewModel.uiState.value.startedCommandId)
        assertNull(viewModel.uiState.value.startingKey)
        coVerify(exactly = 1) { usageRepository.refresh() }
    }

    @Test
    fun `startGoal reports the server error for a blocked goal and does not refresh usage`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoal("myapp", "2026-09-29-feature", "G02-zweites.md") } throws
                ApiException(409, "Voraussetzungen nicht erfuellt: G01")
        }
        val usageRepository = fakeUsageRepository()
        val viewModel = viewModel(api, usageRepository)

        viewModel.startGoal("2026-09-29-feature", "G02-zweites.md")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Voraussetzungen nicht erfuellt: G01", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.startedCommandId)
        assertNull(viewModel.uiState.value.startingKey)
        coVerify(exactly = 0) { usageRepository.refresh() }
    }

    @Test
    fun `startGoal ignores a second call for the same goal while the first is still in flight`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoal("myapp", "2026-09-29-feature", "G01-erstes.md") } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(api)

        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { api.startGoal("myapp", "2026-09-29-feature", "G01-erstes.md") }
    }

    @Test
    fun `consumeStartedCommand clears the started command id`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoal("myapp", "2026-09-29-feature", "G01-erstes.md") } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(api)
        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.consumeStartedCommand()

        assertNull(viewModel.uiState.value.startedCommandId)
    }

    @Test
    fun `folders are collapsed by default and toggleFolder expands and collapses them`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> { coEvery { getGoals("myapp") } returns emptyList() }
        val viewModel = viewModel(api)

        assertTrue(viewModel.uiState.value.expandedFolders.isEmpty())

        viewModel.toggleFolder("2026-09-29-feature")
        assertEquals(setOf("2026-09-29-feature"), viewModel.uiState.value.expandedFolders)

        viewModel.toggleFolder("2026-09-30-other")
        assertEquals(setOf("2026-09-29-feature", "2026-09-30-other"), viewModel.uiState.value.expandedFolders)

        viewModel.toggleFolder("2026-09-29-feature")
        assertEquals(setOf("2026-09-30-other"), viewModel.uiState.value.expandedFolders)
    }

    @Test
    fun `interactive startGoal starts a remote session, keeps usage untouched and reloads the goals`() =
        runTest(dispatcher) {
            val running = listOf(group("2026-09-29-feature", goal("G01", "G01-erstes.md", running = true)))
            val api = mockk<ClServerApi> {
                coEvery { getGoals("myapp") } returnsMany listOf(emptyList(), running)
                coEvery { startGoalInteractive("myapp", "2026-09-29-feature", "G01-erstes.md") } returns
                    RemoteSessionStart(id = "sess-1", output = "backgrounded")
            }
            val usageRepository = fakeUsageRepository()
            val viewModel = viewModel(api, usageRepository)

            viewModel.startGoal("2026-09-29-feature", "G01-erstes.md", interactive = true)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("sess-1", viewModel.uiState.value.startedSessionId)
            assertNull(viewModel.uiState.value.startedCommandId)
            assertNull(viewModel.uiState.value.startingKey)
            assertEquals(running, viewModel.uiState.value.goalGroups)
            coVerify(exactly = 0) { api.startGoal(any(), any(), any()) }
            coVerify(exactly = 0) { usageRepository.refresh() }
        }

    @Test
    fun `interactive startGoal reports the server error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoalInteractive("myapp", "2026-09-29-feature", "G01-erstes.md") } throws
                ApiException(409, "Goal laeuft bereits.")
        }
        val viewModel = viewModel(api)

        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md", interactive = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Goal laeuft bereits.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.startedSessionId)
        assertNull(viewModel.uiState.value.startingKey)
    }

    @Test
    fun `headless startGoal reloads the goals so the running state is visible`() = runTest(dispatcher) {
        val running = listOf(group("2026-09-29-feature", goal("G01", "G01-erstes.md", running = true)))
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returnsMany listOf(emptyList(), running)
            coEvery { startGoal("myapp", "2026-09-29-feature", "G01-erstes.md") } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(api)

        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(running, viewModel.uiState.value.goalGroups)
    }

    @Test
    fun `a failing reload after a successful start does not surface an error`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList() andThenThrows ApiException(500, "Serverfehler.")
            coEvery { startGoal("myapp", "2026-09-29-feature", "G01-erstes.md") } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(api)

        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals("cmd-1", viewModel.uiState.value.startedCommandId)
    }

    @Test
    fun `startGoal ignores a start of another goal while one is in flight`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoal(any(), any(), any()) } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(api)

        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md")
        viewModel.startGoal("2026-09-29-feature", "G02-zweites.md")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { api.startGoal("myapp", "2026-09-29-feature", "G02-zweites.md") }
    }

    @Test
    fun `consumeStartedSession clears the started session id`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getGoals("myapp") } returns emptyList()
            coEvery { startGoalInteractive("myapp", "2026-09-29-feature", "G01-erstes.md") } returns
                RemoteSessionStart(id = "sess-1", output = "")
        }
        val viewModel = viewModel(api)
        viewModel.startGoal("2026-09-29-feature", "G01-erstes.md", interactive = true)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.consumeStartedSession()

        assertNull(viewModel.uiState.value.startedSessionId)
        assertFalse(viewModel.uiState.value.loading)
    }
}
