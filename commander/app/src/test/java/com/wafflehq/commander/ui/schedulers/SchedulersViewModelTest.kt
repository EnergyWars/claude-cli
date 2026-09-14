package com.wafflehq.commander.ui.schedulers

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.PathSchedulerList
import com.wafflehq.commander.data.api.SchedulerSummary
import com.wafflehq.commander.data.api.ScriptSchedulerSummary
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private val SCHEDULER = SchedulerSummary(
    name = "nightly-sync",
    description = "Sync overnight",
    cron = "0 0 3 * * *",
    paths = listOf("myproject"),
)
private val SCRIPT_SCHEDULER = ScriptSchedulerSummary(
    name = "auto-commit-hourly",
    description = "Hourly auto-commit",
    cron = "0 * * * *",
    paths = listOf("myproject"),
    script = "bash auto-commit.sh",
)

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedStateHandle(pathName: String = "myproject") = SavedStateHandle(mapOf("pathName" to pathName))

    @Test
    fun `loads schedulers and script schedulers for this path on init`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getPathSchedulers("myproject") } returns PathSchedulerList(
                schedulers = listOf(SCHEDULER),
                scriptSchedulers = listOf(SCRIPT_SCHEDULER),
            )
        }
        val viewModel = SchedulersViewModel(api, mockk(), savedStateHandle())
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf(SCHEDULER), viewModel.uiState.value.schedulers)
        assertEquals(listOf(SCRIPT_SCHEDULER), viewModel.uiState.value.scriptSchedulers)
        assertEquals(false, viewModel.uiState.value.loading)
    }

    @Test
    fun `trigger on an agent scheduler calls triggerScheduler and refreshes usage`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getPathSchedulers("myproject") } returns PathSchedulerList(emptyList(), emptyList())
            coEvery { triggerScheduler("myproject", "nightly-sync") } returns CommandAccepted("cmd-1")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = SchedulersViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.trigger("nightly-sync", SchedulerKind.AGENT)
        dispatcher.scheduler.runCurrent()

        assertEquals("cmd-1", viewModel.uiState.value.startedCommandId)
        coVerify(exactly = 1) { usageRepository.refresh() }
    }

    @Test
    fun `trigger on a script scheduler calls triggerScriptScheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getPathSchedulers("myproject") } returns PathSchedulerList(emptyList(), emptyList())
            coEvery { triggerScriptScheduler("myproject", "auto-commit-hourly") } returns CommandAccepted("cmd-2")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = SchedulersViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.trigger("auto-commit-hourly", SchedulerKind.SCRIPT)
        dispatcher.scheduler.runCurrent()

        assertEquals("cmd-2", viewModel.uiState.value.startedCommandId)
    }

    @Test
    fun `a failed trigger sets the error and does not refresh usage`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getPathSchedulers("myproject") } returns PathSchedulerList(emptyList(), emptyList())
            coEvery { triggerScheduler("myproject", "nightly-sync") } throws ApiException(400, "Nicht konfiguriert.")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = SchedulersViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.trigger("nightly-sync", SchedulerKind.AGENT)
        dispatcher.scheduler.runCurrent()

        assertEquals("Nicht konfiguriert.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.startedCommandId)
        coVerify(exactly = 0) { usageRepository.refresh() }
    }

    @Test
    fun `consumeStartedCommand clears the started command id`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getPathSchedulers("myproject") } returns PathSchedulerList(emptyList(), emptyList())
            coEvery { triggerScheduler("myproject", "nightly-sync") } returns CommandAccepted("cmd-1")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = SchedulersViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()
        viewModel.trigger("nightly-sync", SchedulerKind.AGENT)
        dispatcher.scheduler.runCurrent()

        viewModel.consumeStartedCommand()

        assertNull(viewModel.uiState.value.startedCommandId)
    }
}
