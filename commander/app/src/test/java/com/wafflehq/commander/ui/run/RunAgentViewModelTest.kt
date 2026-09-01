package com.wafflehq.commander.ui.run

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.ManifestAgent
import com.wafflehq.commander.data.context.DevContextRepository
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
import org.junit.Before
import org.junit.Test

private val AGENT = ManifestAgent(command = "cl dev", description = "Dev agent")
private val MANIFEST = Manifest(agents = listOf(AGENT), paths = emptyList())

private fun fakeDevContextRepository(): DevContextRepository =
    mockk<DevContextRepository> { every { contexts } returns flowOf(emptyList()) }

@OptIn(ExperimentalCoroutinesApi::class)
class RunAgentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedStateHandle() = SavedStateHandle(
        mapOf("agentCommand" to "cl dev", "pathName" to "myproject", "prefillPrompt" to null),
    )

    @Test
    fun `start triggers a usage refresh once the agent run was accepted`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns MANIFEST
            coEvery { runAgent(any(), "myproject", "do the thing", null) } returns CommandAccepted("cmd-1")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = RunAgentViewModel(api, usageRepository, fakeDevContextRepository(), savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.onPromptChange("do the thing")
        viewModel.start()
        dispatcher.scheduler.runCurrent()

        assertEquals("cmd-1", viewModel.uiState.value.createdCommandId)
        coVerify(exactly = 1) { usageRepository.refresh() }
    }

    @Test
    fun `a failed start does not trigger a usage refresh`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns MANIFEST
            coEvery { runAgent(any(), "myproject", "do the thing", null) } throws ApiException(500, "Serverfehler.")
        }
        val usageRepository = mockk<UsageRepository> { coEvery { refresh() } returns Unit }
        val viewModel = RunAgentViewModel(api, usageRepository, fakeDevContextRepository(), savedStateHandle())
        dispatcher.scheduler.runCurrent()

        viewModel.onPromptChange("do the thing")
        viewModel.start()
        dispatcher.scheduler.runCurrent()

        assertEquals("Serverfehler.", viewModel.uiState.value.error)
        coVerify(exactly = 0) { usageRepository.refresh() }
    }
}
