package com.wafflehq.commander.ui.schedulers

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.SchedulerOverview
import com.wafflehq.commander.data.api.SchedulerOverviewList
import com.wafflehq.commander.data.api.SchedulerPathStatus
import com.wafflehq.commander.data.api.ScriptSchedulerOverview
import com.wafflehq.commander.data.api.ScriptSchedulerOverviewList
import io.mockk.coEvery
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
class AllSchedulersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads schedulers and script schedulers across all projects on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSchedulers() } returns SchedulerOverviewList(listOf(SCHEDULER))
            coEvery { getAllScriptSchedulers() } returns ScriptSchedulerOverviewList(listOf(SCRIPT_SCHEDULER))
        }
        val viewModel = AllSchedulersViewModel(api)
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf(SCHEDULER), viewModel.uiState.value.schedulers)
        assertEquals(listOf(SCRIPT_SCHEDULER), viewModel.uiState.value.scriptSchedulers)
        assertEquals(false, viewModel.uiState.value.loading)
    }

    @Test
    fun `a failed refresh sets the error and keeps the list empty`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getAllSchedulers() } throws ApiException(401, "Nicht angemeldet.")
        }
        val viewModel = AllSchedulersViewModel(api)
        dispatcher.scheduler.runCurrent()

        assertEquals("Nicht angemeldet.", viewModel.uiState.value.error)
        assertEquals(emptyList<SchedulerOverview>(), viewModel.uiState.value.schedulers)
        assertEquals(false, viewModel.uiState.value.loading)
    }
}
