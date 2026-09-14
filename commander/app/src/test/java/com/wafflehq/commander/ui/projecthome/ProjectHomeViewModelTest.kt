package com.wafflehq.commander.ui.projecthome

import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.PathSchedulerList
import com.wafflehq.commander.data.api.SchedulerSummary
import com.wafflehq.commander.data.api.ScriptSchedulerSummary
import com.wafflehq.commander.data.api.UsageLimit
import com.wafflehq.commander.data.settings.SettingsRepository
import com.wafflehq.commander.data.usage.UsageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads usage limits on init`() = runTest(dispatcher) {
        val limits = listOf(UsageLimit("Current session", 42, "resets soon"))
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns limits
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(limits, viewModel.uiState.value.usageLimits)
    }

    @Test
    fun `a failed usage load is silently ignored, keeps the previous usage state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(emptyList<UsageLimit>(), viewModel.uiState.value.usageLimits)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `polls usage again after the poll interval elapses`() = runTest(dispatcher) {
        var callCount = 0
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } coAnswers {
                callCount += 1
                listOf(UsageLimit("Current session", callCount * 10, "x"))
            }
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
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
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } coAnswers {
                callCount += 1
                listOf(UsageLimit("Current session", callCount * 10, "x"))
            }
        }
        val usageRepository = UsageRepository(api)
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), usageRepository)
        dispatcher.scheduler.runCurrent()
        assertEquals(10, viewModel.uiState.value.usageLimits.first().percentUsed)

        usageRepository.refresh()
        dispatcher.scheduler.runCurrent()

        assertEquals(20, viewModel.uiState.value.usageLimits.first().percentUsed)
    }

    @Test
    fun `exposes the persisted usage banner expanded state`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns emptyList()
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(usageBannerExpanded = false), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.usageBannerExpanded.value)
    }

    @Test
    fun `persists the usage banner expanded state on change`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getPathSchedulers(any()) } returns EMPTY_PATH_SCHEDULERS
            coEvery { getUsage() } returns emptyList()
        }
        val settingsRepository = fakeSettingsRepository()
        val viewModel = ProjectHomeViewModel(api, settingsRepository, UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        viewModel.onUsageBannerExpandedChanged(false)
        dispatcher.scheduler.runCurrent()

        coVerify { settingsRepository.setUsageBannerExpanded(false) }
    }

    @Test
    fun `hasSchedulers is true when the selected project has a scheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } returns PathSchedulerList(
                schedulers = listOf(SchedulerSummary("nightly-sync", "Sync", "0 0 3 * * *", listOf("default"))),
                scriptSchedulers = emptyList(),
            )
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.hasSchedulers)
    }

    @Test
    fun `hasSchedulers is true when the selected project only has a script scheduler`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } returns PathSchedulerList(
                schedulers = emptyList(),
                scriptSchedulers = listOf(
                    ScriptSchedulerSummary("auto-commit", "Auto-Commit", "0 * * * *", listOf("default"), "echo hi"),
                ),
            )
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.hasSchedulers)
    }

    @Test
    fun `hasSchedulers is false when the selected project has none configured`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } returns EMPTY_PATH_SCHEDULERS
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasSchedulers)
    }

    @Test
    fun `hasSchedulers is false and no error is set when loading it fails`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getUsage() } returns emptyList()
            coEvery { getPathSchedulers("default") } throws ApiException(500, "Serverfehler.")
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasSchedulers)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `hasSchedulers is false without a selected project`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns EMPTY_MANIFEST
            coEvery { getUsage() } returns emptyList()
        }
        val viewModel = ProjectHomeViewModel(api, fakeSettingsRepository(selectedProjectName = null), UsageRepository(api))
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasSchedulers)
    }
}
