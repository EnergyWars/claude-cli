package com.wafflehq.commander.ui.command

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.download.HostedFileDownloader
import com.wafflehq.commander.data.download.PendingInstall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import org.junit.Assert.assertEquals
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

    private fun downloader(
        cachedInstalls: List<PendingInstall> = emptyList(),
        block: HostedFileDownloader.() -> Unit = {},
    ): HostedFileDownloader = mockk(relaxed = true) {
        every { downloadStatus } returns MutableStateFlow(null)
        every { pendingInstalls } returns flowOf(cachedInstalls)
        block()
    }

    private fun viewModel(
        downloader: HostedFileDownloader,
        api: ClServerApi = mockk { every { streamState("cmd-1") } returns flowOf(state) },
    ): CommandDetailViewModel {
        val viewModel = CommandDetailViewModel(
            api,
            downloader,
            SavedStateHandle(mapOf("id" to "cmd-1", "pathName" to "commander")),
        )
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `pendingInstalls exposes cached identities for the current path only`() = runTest(dispatcher) {
        val downloader = downloader(
            cachedInstalls = listOf(
                PendingInstall("commander::debug-apk", "/tmp/app-debug.apk"),
                PendingInstall("other-project::debug-apk", "/tmp/other.apk"),
            ),
        )
        val viewModel = viewModel(downloader)

        assertEquals(setOf("debug-apk"), viewModel.pendingInstalls.value)
    }

    @Test
    fun `download reuses a cached pending install without redownloading`() = runTest(dispatcher) {
        val cached = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("commander", "debug-apk") } returns cached
            coEvery { downloadEntry(any(), any()) } throws AssertionError("should not redownload a cached file")
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(cached, viewModel.uiState.value.downloadedFile)
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)
    }

    @Test
    fun `deleteDownloadedFile removes the file from disk and clears the dialog state`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("commander", "debug-apk") } returns null
            coEvery { downloadEntry("commander", "debug-apk") } returns downloaded
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        assertNull(viewModel.uiState.value.downloadingName)
        coVerify { downloader.deletePendingInstall("commander", "debug-apk", downloaded) }
    }

    @Test
    fun `deleteDownloadedFile does nothing when there is no downloaded file`() = runTest(dispatcher) {
        val downloader = downloader()
        val viewModel = viewModel(downloader)

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        coVerify(exactly = 0) { downloader.deletePendingInstall(any(), any(), any()) }
    }

    @Test
    fun `stop calls stopCommand and clears the stopping flag when it completes`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flowOf(state)
            coEvery { stopCommand("cmd-1") } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(downloader(), api)

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { api.stopCommand("cmd-1") }
        assertEquals(false, viewModel.uiState.value.stopping)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `stop surfaces an error when stopCommand fails`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flowOf(state)
            coEvery { stopCommand("cmd-1") } throws ApiException(409, "Command laeuft nicht mehr.")
        }
        val viewModel = viewModel(downloader(), api)

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Command laeuft nicht mehr.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.stopping)
    }

    @Test
    fun `stop does nothing while a stop is already in flight`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flowOf(state)
            coEvery { stopCommand("cmd-1") } returns CommandAccepted("cmd-1")
        }
        val viewModel = viewModel(downloader(), api)

        viewModel.stop()
        dispatcher.scheduler.runCurrent()
        assertEquals(true, viewModel.uiState.value.stopping)

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { api.stopCommand("cmd-1") }
    }
}
