package com.wafflehq.commander.ui.command

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.CommandAccepted
import com.wafflehq.commander.data.api.CommandState
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.api.ManifestPath
import com.wafflehq.commander.data.download.DownloadOutcome
import com.wafflehq.commander.data.download.DownloadTarget
import com.wafflehq.commander.data.download.DownloadVersion
import com.wafflehq.commander.data.download.HostedFileDownloader
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
        cachedInstalls: List<DownloadVersion> = emptyList(),
        activeTargetFlow: MutableStateFlow<DownloadTarget?> = MutableStateFlow(null),
        outcomeFlow: MutableStateFlow<DownloadOutcome?> = MutableStateFlow(null),
        block: HostedFileDownloader.() -> Unit = {},
    ): HostedFileDownloader = mockk(relaxed = true) {
        every { downloadStatus } returns MutableStateFlow(null)
        every { pendingInstalls } returns flowOf(cachedInstalls)
        every { activeTarget } returns activeTargetFlow
        every { downloadOutcome } returns outcomeFlow
        every { startDownload(any(), any(), any(), any()) } returns true
        block()
    }

    private fun viewModel(
        downloader: HostedFileDownloader,
        api: ClServerApi = mockk {
            every { streamState("cmd-1") } returns flowOf(state)
            coEvery { getManifest() } returns Manifest(emptyList(), emptyList())
        },
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
                DownloadVersion("commander::debug-apk", null, "2026-08-26T00:00:00Z", "/tmp/app-debug.apk"),
                DownloadVersion("other-project::debug-apk", null, "2026-08-26T00:00:00Z", "/tmp/other.apk"),
            ),
        )
        val viewModel = viewModel(downloader)

        assertEquals(setOf("debug-apk"), viewModel.pendingInstalls.value)
    }

    @Test
    fun `hostedFiles keeps the build timestamp of each entry`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flowOf(state)
            coEvery { getManifest() } returns Manifest(
                emptyList(),
                listOf(
                    ManifestPath(
                        "commander",
                        emptyList(),
                        listOf(
                            ManifestHostedEntry("debug-apk", "file", "2026-08-26T00:00:00.000Z"),
                            ManifestHostedEntry("builds", "path", null),
                        ),
                    ),
                ),
            )
        }
        val viewModel = viewModel(downloader(), api)

        assertEquals(
            listOf(ManifestHostedEntry("debug-apk", "file", "2026-08-26T00:00:00.000Z")),
            viewModel.uiState.value.hostedFiles,
        )
    }

    @Test
    fun `download reuses a cached pending install without redownloading`() = runTest(dispatcher) {
        val cached = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("commander", "debug-apk", null) } returns cached
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(cached, viewModel.uiState.value.downloadedFile)
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)
        coVerify(exactly = 0) { downloader.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `downloadingName is set while the download runs and cleared once consumed`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val outcomeFlow = MutableStateFlow<DownloadOutcome?>(null)
        val downloader = downloader(outcomeFlow = outcomeFlow) {
            coEvery { resolvePendingInstall("commander", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.runCurrent()
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)

        outcomeFlow.value = DownloadOutcome.Success(
            DownloadTarget("commander", "debug-apk", "debug-apk", null, null),
            downloaded,
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(downloaded, viewModel.uiState.value.downloadedFile)
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)

        viewModel.consumeDownloadedFile()
        assertNull(viewModel.uiState.value.downloadingName)
        assertNull(viewModel.uiState.value.downloadedFile)
    }

    @Test
    fun `a failed download clears downloadingName and reports the error`() = runTest(dispatcher) {
        val outcomeFlow = MutableStateFlow<DownloadOutcome?>(null)
        val downloader = downloader(outcomeFlow = outcomeFlow) {
            coEvery { resolvePendingInstall("commander", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.runCurrent()
        outcomeFlow.value = DownloadOutcome.Failure(
            DownloadTarget("commander", "debug-apk", "debug-apk", null, null),
            "Download fehlgeschlagen.",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadingName)
        assertEquals("Download fehlgeschlagen.", viewModel.uiState.value.error)
    }

    @Test
    fun `a second download while one is running is ignored`() = runTest(dispatcher) {
        val downloader = downloader {
            coEvery { resolvePendingInstall("commander", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.runCurrent()
        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { downloader.startDownload("commander", "debug-apk", null, null) }
    }

    @Test
    fun `download checks the manifest for the current server timestamp before resolving the cache`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flowOf(state)
            coEvery { getManifest() } returns Manifest(
                emptyList(),
                listOf(
                    ManifestPath(
                        "commander",
                        emptyList(),
                        listOf(ManifestHostedEntry("debug-apk", "file", "2026-08-26T00:00:00.000Z")),
                    ),
                ),
            )
        }
        val downloader = downloader {
            coEvery { resolvePendingInstall("commander", "debug-apk", "2026-08-26T00:00:00.000Z") } returns null
        }
        val viewModel = viewModel(downloader, api)

        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { downloader.startDownload("commander", "debug-apk", null, "2026-08-26T00:00:00.000Z") }
    }

    @Test
    fun `deleteDownloadedFile removes the file from disk and clears the dialog state`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val outcomeFlow = MutableStateFlow<DownloadOutcome?>(null)
        val downloader = downloader(outcomeFlow = outcomeFlow) {
            coEvery { resolvePendingInstall("commander", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.runCurrent()
        outcomeFlow.value = DownloadOutcome.Success(
            DownloadTarget("commander", "debug-apk", "debug-apk", null, null),
            downloaded,
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(downloaded, viewModel.uiState.value.downloadedFile)

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        assertNull(viewModel.uiState.value.downloadingName)
        coVerify { downloader.deletePendingInstall("commander", "debug-apk", downloaded) }
    }

    @Test
    fun `startDownload refused globally surfaces an error and clears downloadingName`() = runTest(dispatcher) {
        val downloader = downloader {
            coEvery { resolvePendingInstall("commander", "debug-apk", null) } returns null
            every { startDownload("commander", "debug-apk", null, null) } returns false
        }
        val viewModel = viewModel(downloader)

        viewModel.download("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadingName)
        assertEquals("Ein anderer Download läuft bereits.", viewModel.uiState.value.error)
    }

    @Test
    fun `a download already running for this path when the screen opens is reflected immediately`() = runTest(dispatcher) {
        val activeTargetFlow = MutableStateFlow<DownloadTarget?>(
            DownloadTarget("commander", "debug-apk", "debug-apk", null, null),
        )
        val downloader = downloader(activeTargetFlow = activeTargetFlow)

        val viewModel = viewModel(downloader)

        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)
    }

    @Test
    fun `an active download for a different path is ignored when the screen opens`() = runTest(dispatcher) {
        val activeTargetFlow = MutableStateFlow<DownloadTarget?>(
            DownloadTarget("other-project", "debug-apk", "debug-apk", null, null),
        )
        val downloader = downloader(activeTargetFlow = activeTargetFlow)

        val viewModel = viewModel(downloader)

        assertNull(viewModel.uiState.value.downloadingName)
    }

    @Test
    fun `an outcome for a different path is ignored`() = runTest(dispatcher) {
        val outcomeFlow = MutableStateFlow<DownloadOutcome?>(null)
        val downloader = downloader(outcomeFlow = outcomeFlow)
        val viewModel = viewModel(downloader)

        outcomeFlow.value = DownloadOutcome.Success(
            DownloadTarget("other-project", "debug-apk", "debug-apk", null, null),
            File.createTempFile("commander-test", ".apk"),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        assertNull(viewModel.uiState.value.error)
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
    fun `polling fallback recovers after a transient error instead of freezing on it`() = runTest(dispatcher) {
        var attempt = 0
        val api = mockk<ClServerApi> {
            every { streamState("cmd-1") } returns flow { throw ApiException(null, "Netzwerkfehler.") }
            coEvery { getState("cmd-1") } coAnswers {
                attempt++
                if (attempt == 1) throw ApiException(null, "Netzwerkfehler.") else state
            }
        }
        val viewModel = viewModel(downloader(), api)

        assertEquals(state, viewModel.uiState.value.state)
        assertNull(viewModel.uiState.value.error)
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
