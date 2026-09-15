package com.wafflehq.commander.ui.schedulers

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SchedulerEnabledUpdate
import com.wafflehq.commander.data.api.SchedulerOverview
import com.wafflehq.commander.data.api.SchedulerOverviewList
import com.wafflehq.commander.data.api.SchedulerPathStatus
import com.wafflehq.commander.data.api.ScriptSchedulerOverview
import com.wafflehq.commander.data.api.ScriptSchedulerOverviewList
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

private val SCHEDULER = SchedulerOverview(
    name = "nightly-sync",
    description = "Sync overnight",
    cron = "0 0 3 * * *",
    paths = listOf("myproject", "otherproject"),
    instructions = "# Nightly-Sync-Context\n",
    pathStatuses = listOf(
        SchedulerPathStatus("myproject", true),
        SchedulerPathStatus("otherproject", false),
    ),
)
private val SCRIPT_SCHEDULER = ScriptSchedulerOverview(
    name = "auto-commit-hourly",
    description = "Hourly auto-commit",
    cron = "0 * * * *",
    paths = listOf("myproject"),
    script = "bash auto-commit.sh",
    pathStatuses = listOf(SchedulerPathStatus("myproject", true)),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedStateHandle(name: String, kind: SchedulerKind) =
        SavedStateHandle(mapOf("name" to name, "kind" to kind.name))

    @Test
    fun `loads the resolved instructions and path statuses for an agent scheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSchedulers() } returns SchedulerOverviewList(listOf(SCHEDULER))
        }
        val viewModel = SchedulerDetailViewModel(api, savedStateHandle("nightly-sync", SchedulerKind.AGENT))
        dispatcher.scheduler.runCurrent()

        assertEquals("Sync overnight", viewModel.uiState.value.description)
        assertEquals("# Nightly-Sync-Context\n", viewModel.uiState.value.executionText)
        assertEquals(SCHEDULER.pathStatuses, viewModel.uiState.value.pathStatuses)
        assertEquals(false, viewModel.uiState.value.loading)
    }

    @Test
    fun `loads the script for a script scheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllScriptSchedulers() } returns ScriptSchedulerOverviewList(listOf(SCRIPT_SCHEDULER))
        }
        val viewModel = SchedulerDetailViewModel(api, savedStateHandle("auto-commit-hourly", SchedulerKind.SCRIPT))
        dispatcher.scheduler.runCurrent()

        assertEquals("bash auto-commit.sh", viewModel.uiState.value.executionText)
        assertEquals(SCRIPT_SCHEDULER.pathStatuses, viewModel.uiState.value.pathStatuses)
    }

    @Test
    fun `sets an error when the scheduler is no longer in the list`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSchedulers() } returns SchedulerOverviewList(emptyList())
        }
        val viewModel = SchedulerDetailViewModel(api, savedStateHandle("nightly-sync", SchedulerKind.AGENT))
        dispatcher.scheduler.runCurrent()

        assertEquals("Scheduler nicht gefunden.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.loading)
    }

    @Test
    fun `setEnabled on a path calls setSchedulerEnabled and updates only that path`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSchedulers() } returns SchedulerOverviewList(listOf(SCHEDULER))
            coEvery { setSchedulerEnabled("otherproject", "nightly-sync", true) } returns
                SchedulerEnabledUpdate("nightly-sync", "otherproject", true)
        }
        val viewModel = SchedulerDetailViewModel(api, savedStateHandle("nightly-sync", SchedulerKind.AGENT))
        dispatcher.scheduler.runCurrent()

        viewModel.setEnabled("otherproject", true)
        dispatcher.scheduler.runCurrent()

        val statuses = viewModel.uiState.value.pathStatuses.associate { it.pathName to it.enabled }
        assertEquals(true, statuses["otherproject"])
        assertEquals(true, statuses["myproject"])
        coVerify(exactly = 1) { api.setSchedulerEnabled("otherproject", "nightly-sync", true) }
    }

    @Test
    fun `setEnabled for a script scheduler calls setScriptSchedulerEnabled`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllScriptSchedulers() } returns ScriptSchedulerOverviewList(listOf(SCRIPT_SCHEDULER))
            coEvery { setScriptSchedulerEnabled("myproject", "auto-commit-hourly", false) } returns
                SchedulerEnabledUpdate("auto-commit-hourly", "myproject", false)
        }
        val viewModel = SchedulerDetailViewModel(api, savedStateHandle("auto-commit-hourly", SchedulerKind.SCRIPT))
        dispatcher.scheduler.runCurrent()

        viewModel.setEnabled("myproject", false)
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.pathStatuses.single().enabled)
        coVerify(exactly = 1) { api.setScriptSchedulerEnabled("myproject", "auto-commit-hourly", false) }
    }

    @Test
    fun `a failed setEnabled call sets the error and keeps the previous status`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSchedulers() } returns SchedulerOverviewList(listOf(SCHEDULER))
            coEvery { setSchedulerEnabled("myproject", "nightly-sync", false) } throws
                ApiException(404, "Unbekannter Scheduler.")
        }
        val viewModel = SchedulerDetailViewModel(api, savedStateHandle("nightly-sync", SchedulerKind.AGENT))
        dispatcher.scheduler.runCurrent()

        viewModel.setEnabled("myproject", false)
        dispatcher.scheduler.runCurrent()

        assertEquals("Unbekannter Scheduler.", viewModel.uiState.value.error)
        val statuses = viewModel.uiState.value.pathStatuses.associate { it.pathName to it.enabled }
        assertEquals(true, statuses["myproject"])
        assertEquals(null, viewModel.uiState.value.updatingPathName)
    }
}
