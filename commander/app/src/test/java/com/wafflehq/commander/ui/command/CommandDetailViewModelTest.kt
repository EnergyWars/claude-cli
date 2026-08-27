package com.wafflehq.commander.ui.command

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.download.HostedFileDownloader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val state = CommandState(
        id = "cmd-1",
        agent = "agent",
        model = "model",
        command = "echo hi",
        path = "commander",
        status = "completed",
        output = "hi",
        exitCode = 0,
        createdAt = "2026-08-26T00:00:00.000Z",
        updatedAt = "2026-08-26T00:00:01.000Z",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(downloader: HostedFileDownloader): CommandDetailViewModel {
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flowOf(state)
        }
        val viewModel = CommandDetailViewModel(
            api,
            downloader,
            SavedStateHandle(mapOf("id" to "cmd-1", "pathName" to "commander")),
        )
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `deleteDownloadedFile removes the file from disk and clears the dialog state`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val downloader = mockk<HostedFileDownloader> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
            coEvery { downloadEntry("commander", "debug-apk") } returns downloaded
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        assertNull(viewModel.uiState.value.downloadingName)
        assert(!downloaded.exists())
    }

    @Test
    fun `deleteDownloadedFile does nothing when there is no downloaded file`() = runTest(dispatcher) {
        val downloader = mockk<HostedFileDownloader> { every { downloadStatus } returns MutableStateFlow(null) }
        val viewModel = viewModel(downloader)

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
    }
}
