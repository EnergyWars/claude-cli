package com.wafflehq.commander.ui.commands

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.ManifestPath
import com.wafflehq.commander.data.api.PathCommandEntry
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
import org.junit.Before
import org.junit.Test

private val COMMAND = PathCommandEntry(key = "build", command = "build", displayName = "Build", description = "")

private fun manifestWith(pathName: String) =
    Manifest(agents = emptyList(), paths = listOf(ManifestPath(name = pathName, commands = listOf(COMMAND), hosted = emptyList())))

@OptIn(ExperimentalCoroutinesApi::class)
class CommandsViewModelTest {

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
    fun `runCommand triggers a usage refresh after the command was accepted`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns manifestWith("myproject")
            coEvery { runPathCommand("myproject", "build") } returns CommandAccepted("cmd-1")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = CommandsViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.runCommand("build")
        dispatcher.scheduler.runCurrent()

        assertEquals("cmd-1", viewModel.uiState.value.startedCommandId)
        coVerify(exactly = 1) { usageRepository.refresh() }
    }

    @Test
    fun `a failed runCommand does not trigger a usage refresh`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns manifestWith("myproject")
            coEvery { runPathCommand("myproject", "build") } throws ApiException(500, "Serverfehler.")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = CommandsViewModel(api, usageRepository, savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.runCommand("build")
        dispatcher.scheduler.runCurrent()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        coVerify(exactly = 0) { usageRepository.refresh() }
    }
}
