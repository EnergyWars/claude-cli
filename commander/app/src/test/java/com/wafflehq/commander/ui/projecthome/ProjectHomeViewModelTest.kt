package com.wafflehq.commander.ui.projecthome

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.GoalEntry
import com.wafflehq.commander.data.api.GoalListGroup
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.PathSchedulerList
import com.wafflehq.commander.data.api.RemoteSessionStart
import com.wafflehq.commander.data.api.SchedulerSummary
import com.wafflehq.commander.data.api.ScriptSchedulerSummary
import com.wafflehq.commander.data.api.UsageLimit
import com.wafflehq.commander.data.settings.SettingsRepository
import com.wafflehq.commander.data.usage.UsageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private const val USAGE_POLL_INTERVAL_MS = 60_000L

private fun fakeSettingsRepository(
    selectedProjectName: String? = "default",
    usageBannerExpanded: Boolean = true,
): SettingsRepository =
    mockk<SettingsRepository> {
        every { this@mockk.selectedProjectName } returns flowOf(selectedProjectName)
        every { this@mockk.usageBannerExpanded } returns flowOf(usageBannerExpanded)
        coEvery { setUsageBannerExpanded(any()) } returns Unit
    }

private val EMPTY_MANIFEST = Manifest(agents = emptyList(), paths = emptyList())
private val EMPTY_PATH_SCHEDULERS = PathSchedulerList(schedulers = emptyList(), scriptSchedulers = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectHomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    private fun TestScope.newViewModel(
        api: ClServerApi,
        settingsRepository: SettingsRepository,
        usageRepository: UsageRepository = UsageRepository(api),
    ): ProjectHomeViewModel {
        val viewModel = ProjectHomeViewModel(api, settingsRepository, usageRepository)
        backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { viewModel.viewModelScope.cancel() }
        return viewModel
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads usage limits on init`() = runTest(dispatcher) {
        val limits = listOf(UsageLimit("Current session", 42, "resets soon"))
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns limits
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(limits, viewModel.uiState.value.usageLimits)
    }

    @Test
    fun `a failed usage load is silently ignored, keeps the previous usage state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(emptyList<UsageLimit>(), viewModel.uiState.value.usageLimits)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `polls usage again after the poll interval elapses`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } coAnswers {
                callCount += 1
                listOf(UsageLimit("Current session", callCount * 10, "x"))
            }
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()
        assertEquals(10, viewModel.uiState.value.usageLimits.first().percentUsed)

        dispatcher.scheduler.advanceTimeBy(USAGE_POLL_INTERVAL_MS)
        dispatcher.scheduler.runCurrent()

        assertEquals(20, viewModel.uiState.value.usageLimits.first().percentUsed)
    }

    @Test
    fun `reflects a usage refresh triggered from outside the view model, eg by firing a command`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } coAnswers {
                callCount += 1
                listOf(UsageLimit("Current session", callCount * 10, "x"))
            }
        }
        val usageRepository = UsageRepository(api)
        val viewModel = newViewModel(api, fakeSettingsRepository(), usageRepository)
        dispatcher.scheduler.runCurrent()
        assertEquals(10, viewModel.uiState.value.usageLimits.first().percentUsed)

        usageRepository.refresh()
        dispatcher.scheduler.runCurrent()

        assertEquals(20, viewModel.uiState.value.usageLimits.first().percentUsed)
    }

    @Test
    fun `usageLastUpdatedAt is set after the initial load and updates again on refreshUsage`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns emptyList()
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        val firstUpdate = viewModel.uiState.value.usageLastUpdatedAt
        assertEquals(true, firstUpdate != null)

        viewModel.refreshUsage()
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.usageLastUpdatedAt != null)
    }

    @Test
    fun `refreshUsage triggers an additional fetch on demand`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } coAnswers {
                callCount += 1
                listOf(UsageLimit("Current session", callCount * 10, "x"))
            }
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()
        assertEquals(10, viewModel.uiState.value.usageLimits.first().percentUsed)

        viewModel.refreshUsage()
        dispatcher.scheduler.runCurrent()

        assertEquals(20, viewModel.uiState.value.usageLimits.first().percentUsed)
    }

    @Test
    fun `exposes the persisted usage banner expanded state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns emptyList()
        }
        val viewModel = newViewModel(api, fakeSettingsRepository(usageBannerExpanded = false))
        backgroundScope.launch { viewModel.usageBannerExpanded.collect {} }
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.usageBannerExpanded.value)
    }

    @Test
    fun `persists the usage banner expanded state on change`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns emptyList()
        }
        val settingsRepository = fakeSettingsRepository()
        val viewModel = newViewModel(api, settingsRepository)
        dispatcher.scheduler.runCurrent()

        viewModel.onUsageBannerExpandedChanged(false)
        dispatcher.scheduler.runCurrent()

        coVerify { settingsRepository.setUsageBannerExpanded(false) }
    }

    @Test
    fun `hasSchedulers is true when the selected project has a scheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } returns PathSchedulerList(
                schedulers = listOf(SchedulerSummary("nightly-sync", "Sync", "0 0 3 * * *", listOf("default"))),
                scriptSchedulers = emptyList(),
            )
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.hasSchedulers)
    }

    @Test
    fun `hasSchedulers is true when the selected project only has a script scheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } returns PathSchedulerList(
                schedulers = emptyList(),
                scriptSchedulers = listOf(
                    ScriptSchedulerSummary("auto-commit", "Auto-Commit", "0 * * * *", listOf("default"), "echo hi"),
                ),
            )
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.hasSchedulers)
    }

    @Test
    fun `hasSchedulers is false when the selected project has none configured`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } returns EMPTY_PATH_SCHEDULERS
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasSchedulers)
    }

    @Test
    fun `hasSchedulers is false and no error is set when loading it fails`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasSchedulers)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `hasSchedulers is false without a selected project`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getGoals(any()) } returns emptyList()
            coEvery { getUsage() } returns emptyList()
        }
        val viewModel = newViewModel(api, fakeSettingsRepository(selectedProjectName = null))
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasSchedulers)
    }

    private fun goal(fileName: String, status: String = "ready", running: Boolean = false) = GoalEntry(
        id = fileName,
        fileName = fileName,
        title = fileName,
        description = "",
        date = "",
        dependsOn = emptyList(),
        command = "/goal $fileName",
        content = "",
        timestamp = "",
        legacy = false,
        status = status,
        missingDependencies = emptyList(),
        running = running,
    )

    private fun goalsApi(vararg groups: GoalListGroup) = mockk<ClServerApi> {
        coEvery { getManifest() } returns EMPTY_MANIFEST
        coEvery { getUsage() } returns emptyList()
        coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
        coEvery { getGoals("default") } returns groups.toList()
    }

    @Test
    fun `startableGoalCount sums ready and not running goals across folders`() = runTest(dispatcher) {
        val api = goalsApi(
            GoalListGroup("a", goals = listOf(goal("1.md"), goal("2.md", running = true), goal("3.md", status = "blocked"))),
            GoalListGroup("b", goals = listOf(goal("4.md"))),
        )
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(2, viewModel.uiState.value.startableGoalCount)
    }

    @Test
    fun `startableGoalCount is zero when loading goals fails`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.getGoals("default") } throws ApiException(500, "Serverfehler.")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        assertEquals(0, viewModel.uiState.value.startableGoalCount)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `startableGoalCount is zero without a selected project`() = runTest(dispatcher) {
        val api = goalsApi(GoalListGroup("a", goals = listOf(goal("1.md"))))
        val viewModel = newViewModel(api, fakeSettingsRepository(selectedProjectName = null))
        dispatcher.scheduler.runCurrent()

        assertEquals(0, viewModel.uiState.value.startableGoalCount)
    }

    @Test
    fun `startableGoalCount is polled again after the poll interval`() = runTest(dispatcher) {
        var calls = 0
        val api = goalsApi()
        coEvery { api.getGoals("default") } coAnswers {
            calls += 1
            listOf(GoalListGroup("a", goals = List(calls) { goal("$it.md") }))
        }
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()
        assertEquals(1, viewModel.uiState.value.startableGoalCount)

        dispatcher.scheduler.advanceTimeBy(10_000L)
        dispatcher.scheduler.runCurrent()

        assertEquals(2, viewModel.uiState.value.startableGoalCount)
    }

    @Test
    fun `startAllGoals starts every ready goal, skips blocked and running ones and refreshes usage`() = runTest(dispatcher) {
        val api = goalsApi(
            GoalListGroup("a", goals = listOf(goal("1.md"), goal("2.md", running = true), goal("3.md", status = "blocked"))),
            GoalListGroup("b", goals = listOf(goal("4.md"))),
        )
        coEvery { api.startGoal(any(), any(), any(), any()) } returns CommandAccepted("cmd")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.startAllGoals()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { api.startGoal("default", "a", "1.md", "sonnet") }
        coVerify(exactly = 1) { api.startGoal("default", "b", "4.md", "sonnet") }
        coVerify(exactly = 2) { api.startGoal(any(), any(), any(), any()) }
        coVerify(atLeast = 2) { api.getUsage() }
        assertEquals(false, viewModel.uiState.value.startingAllGoals)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `startAllGoals continues after a failing goal and reports the error`() = runTest(dispatcher) {
        val api = goalsApi(GoalListGroup("a", goals = listOf(goal("1.md"), goal("2.md"))))
        coEvery { api.startGoal("default", "a", "1.md", "sonnet") } throws ApiException(409, "Bereits laufend.")
        coEvery { api.startGoal("default", "a", "2.md", "sonnet") } returns CommandAccepted("cmd")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.startAllGoals()
        dispatcher.scheduler.runCurrent()

        coVerify { api.startGoal("default", "a", "2.md", "sonnet") }
        assertEquals("Bereits laufend.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.startingAllGoals)
    }

    @Test
    fun `startAllGoals reports an error when the goals cannot be loaded and does not refresh usage`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.getGoals("default") } throws ApiException(500, "Serverfehler.")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.startAllGoals()
        dispatcher.scheduler.runCurrent()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        coVerify(exactly = 0) { api.startGoal(any(), any(), any(), any()) }
    }

    @Test
    fun `startAllGoals does nothing without a selected project`() = runTest(dispatcher) {
        val api = goalsApi()
        val viewModel = newViewModel(api, fakeSettingsRepository(selectedProjectName = null))
        dispatcher.scheduler.runCurrent()

        viewModel.startAllGoals()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.startingAllGoals)
        coVerify(exactly = 0) { api.startGoal(any(), any(), any(), any()) }
    }

    @Test
    fun `startAllGoals uses the selected model and defaults to sonnet`() = runTest(dispatcher) {
        val api = goalsApi(GoalListGroup("a", goals = listOf(goal("1.md"))))
        coEvery { api.startGoal(any(), any(), any(), any()) } returns CommandAccepted("cmd")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()
        assertEquals("sonnet", viewModel.uiState.value.startAllModel)

        viewModel.onStartAllModelSelected("opus")
        viewModel.startAllGoals()
        dispatcher.scheduler.runCurrent()

        assertEquals("opus", viewModel.uiState.value.startAllModel)
        coVerify(exactly = 1) { api.startGoal("default", "a", "1.md", "opus") }
    }

    @Test
    fun `startAllGoals starts at most the configured maximum number of goals`() = runTest(dispatcher) {
        val api = goalsApi(
            GoalListGroup("a", goals = listOf(goal("1.md"), goal("2.md"))),
            GoalListGroup("b", goals = listOf(goal("3.md"))),
        )
        coEvery { api.startGoal(any(), any(), any(), any()) } returns CommandAccepted("cmd")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.onStartAllMaxCountChanged("2")
        viewModel.startAllGoals()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { api.startGoal("default", "a", "1.md", "sonnet") }
        coVerify(exactly = 1) { api.startGoal("default", "a", "2.md", "sonnet") }
        coVerify(exactly = 2) { api.startGoal(any(), any(), any(), any()) }
    }

    @Test
    fun `onStartAllMaxCountChanged keeps digits only and treats empty or zero as unlimited`() = runTest(dispatcher) {
        val api = goalsApi()
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.startAllMaxCount)

        viewModel.onStartAllMaxCountChanged("a3b")
        assertEquals(3, viewModel.uiState.value.startAllMaxCount)

        viewModel.onStartAllMaxCountChanged("0")
        assertNull(viewModel.uiState.value.startAllMaxCount)

        viewModel.onStartAllMaxCountChanged("12345")
        assertEquals(1234, viewModel.uiState.value.startAllMaxCount)

        viewModel.onStartAllMaxCountChanged("")
        assertNull(viewModel.uiState.value.startAllMaxCount)
    }

    @Test
    fun `startRemoteSession starts a session with the default model and exposes its id`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.startRemoteSession("default", null, "sonnet") } returns RemoteSessionStart("sess-1", "")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.startRemoteSession()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { api.startRemoteSession("default", null, "sonnet") }
        assertEquals("sess-1", viewModel.uiState.value.remoteSessionStartedId)
        assertEquals(false, viewModel.uiState.value.startingRemoteSession)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `startRemoteSession reports the error when starting fails`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.startRemoteSession(any(), any(), any()) } throws ApiException(500, "Fehlgeschlagen.")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.startRemoteSession()
        dispatcher.scheduler.runCurrent()

        assertEquals("Fehlgeschlagen.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.remoteSessionStartedId)
        assertEquals(false, viewModel.uiState.value.startingRemoteSession)
    }

    @Test
    fun `startRemoteSession does nothing without a selected project`() = runTest(dispatcher) {
        val api = goalsApi()
        val viewModel = newViewModel(api, fakeSettingsRepository(selectedProjectName = null))
        dispatcher.scheduler.runCurrent()

        viewModel.startRemoteSession()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { api.startRemoteSession(any(), any(), any()) }
    }

    @Test
    fun `runGoalCommand appends the text to goal-plan and exposes the command id`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.runAgent(null, "default", "/goal-plan Neue Suche bauen", null) } returns CommandAccepted("cmd-1")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.runGoalCommand(GoalCommand.Plan, "  Neue Suche bauen  ")
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { api.runAgent(null, "default", "/goal-plan Neue Suche bauen", null) }
        assertEquals("cmd-1", viewModel.uiState.value.startedCommandId)
        assertEquals(false, viewModel.uiState.value.startingGoalCommand)
        coVerify(atLeast = 2) { api.getUsage() }
    }

    @Test
    fun `runGoalCommand appends the text to goal-prompt`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.runAgent(any(), any(), any(), any()) } returns CommandAccepted("cmd-2")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.runGoalCommand(GoalCommand.Prompt, "Dark Mode")
        dispatcher.scheduler.runCurrent()

        coVerify { api.runAgent(null, "default", "/goal-prompt Dark Mode", null) }
    }

    @Test
    fun `runGoalCommand ignores blank text`() = runTest(dispatcher) {
        val api = goalsApi()
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.runGoalCommand(GoalCommand.Plan, "   ")
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { api.runAgent(any(), any(), any(), any()) }
    }

    @Test
    fun `runGoalCommand reports the error when running fails`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.runAgent(any(), any(), any(), any()) } throws ApiException(500, "Fehlgeschlagen.")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.runGoalCommand(GoalCommand.Plan, "x")
        dispatcher.scheduler.runCurrent()

        assertEquals("Fehlgeschlagen.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.startedCommandId)
        assertEquals(false, viewModel.uiState.value.startingGoalCommand)
    }

    @Test
    fun `runGoalCommand does nothing without a selected project`() = runTest(dispatcher) {
        val api = goalsApi()
        val viewModel = newViewModel(api, fakeSettingsRepository(selectedProjectName = null))
        dispatcher.scheduler.runCurrent()

        viewModel.runGoalCommand(GoalCommand.Plan, "x")
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { api.runAgent(any(), any(), any(), any()) }
    }

    @Test
    fun `onStartedCommandConsumed clears the started command id`() = runTest(dispatcher) {
        val api = goalsApi()
        coEvery { api.runAgent(any(), any(), any(), any()) } returns CommandAccepted("cmd-3")
        val viewModel = newViewModel(api, fakeSettingsRepository())
        dispatcher.scheduler.runCurrent()
        viewModel.runGoalCommand(GoalCommand.Plan, "x")
        dispatcher.scheduler.runCurrent()

        viewModel.onStartedCommandConsumed()

        assertNull(viewModel.uiState.value.startedCommandId)
    }
}
