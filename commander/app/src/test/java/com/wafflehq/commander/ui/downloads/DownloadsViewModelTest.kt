package com.wafflehq.commander.ui.downloads

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.FileList
import com.wafflehq.commander.data.api.HostedFileEntry
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.api.ManifestHostedEntry
import com.wafflehq.commander.data.api.ManifestPath
import com.wafflehq.commander.data.download.DownloadOutcome
import com.wafflehq.commander.data.download.DownloadPhase
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.DownloadTarget
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
class DownloadsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<ClServerApi> {
        coEvery { getManifest() } returns Manifest(emptyList(), emptyList())
    }

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

    private fun viewModel(downloader: HostedFileDownloader, api: ClServerApi = this.api): DownloadsViewModel {
        val viewModel = DownloadsViewModel(api, downloader, SavedStateHandle(mapOf("pathName" to "periodical")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `downloadingName is set while the download runs and cleared once consumed`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val outcomeFlow = MutableStateFlow<DownloadOutcome?>(null)
        val downloader = downloader(outcomeFlow = outcomeFlow) {
            coEvery { resolvePendingInstall("periodical", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.runCurrent()
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)

        outcomeFlow.value = DownloadOutcome.Success(
            DownloadTarget("periodical", "debug-apk", "debug-apk", null, null),
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
            coEvery { resolvePendingInstall("periodical", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.runCurrent()
        outcomeFlow.value = DownloadOutcome.Failure(
            DownloadTarget("periodical", "debug-apk", "debug-apk", null, null),
            "Download fehlgeschlagen.",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadingName)
        assertEquals("Download fehlgeschlagen.", viewModel.uiState.value.error)
    }

    @Test
    fun `a second download while one is running is ignored`() = runTest(dispatcher) {
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.runCurrent()
        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { downloader.startDownload("periodical", "debug-apk", null, null) }
    }

    @Test
    fun `startDownload refused globally surfaces an error and clears downloadingName`() = runTest(dispatcher) {
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk", null) } returns null
            every { startDownload("periodical", "debug-apk", null, null) } returns false
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadingName)
        assertEquals("Ein anderer Download läuft bereits.", viewModel.uiState.value.error)
    }

    @Test
    fun `a download already running for this path when the screen opens is reflected immediately`() = runTest(dispatcher) {
        val activeTargetFlow = MutableStateFlow<DownloadTarget?>(
            DownloadTarget("periodical", "debug-apk", "debug-apk", null, null),
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
    fun `downloadStatus passes through the downloader's status flow`() = runTest(dispatcher) {
        val statusFlow = MutableStateFlow<DownloadStatus?>(null)
        val downloader = downloader {
            every { downloadStatus } returns statusFlow
        }
        val viewModel = viewModel(downloader)

        statusFlow.value = DownloadStatus(DownloadPhase.VERIFYING)

        assertEquals(DownloadPhase.VERIFYING, viewModel.downloadStatus.value?.phase)
    }

    @Test
    fun `pendingInstalls exposes cached identities for the current path only`() = runTest(dispatcher) {
        val downloader = downloader(
            cachedInstalls = listOf(
                PendingInstall("periodical::debug-apk", null, "/tmp/app-debug.apk"),
                PendingInstall("other-project::debug-apk", null, "/tmp/other.apk"),
            ),
        )
        val viewModel = viewModel(downloader)

        assertEquals(setOf("debug-apk"), viewModel.pendingInstalls.value)
    }

    @Test
    fun `tapping download again reuses a cached pending install without redownloading`() = runTest(dispatcher) {
        val cached = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk", null) } returns cached
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(cached, viewModel.uiState.value.downloadedFile)
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)
        coVerify(exactly = 0) { downloader.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteDownloadedFile removes the file from disk and clears the dialog state`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val outcomeFlow = MutableStateFlow<DownloadOutcome?>(null)
        val downloader = downloader(outcomeFlow = outcomeFlow) {
            coEvery { resolvePendingInstall("periodical", "debug-apk", null) } returns null
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.runCurrent()
        outcomeFlow.value = DownloadOutcome.Success(
            DownloadTarget("periodical", "debug-apk", "debug-apk", null, null),
            downloaded,
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(downloaded, viewModel.uiState.value.downloadedFile)

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        assertNull(viewModel.uiState.value.downloadingName)
        coVerify { downloader.deletePendingInstall("periodical", "debug-apk", downloaded) }
    }

    @Test
    fun `downloadEntry checks the manifest for the current server timestamp before resolving the cache`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns Manifest(
                emptyList(),
                listOf(
                    ManifestPath(
                        "periodical",
                        emptyList(),
                        listOf(ManifestHostedEntry("debug-apk", "file", "2026-08-26T00:00:00.000Z")),
                    ),
                ),
            )
        }
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk", "2026-08-26T00:00:00.000Z") } returns null
        }
        val viewModel = viewModel(downloader, api)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { downloader.startDownload("periodical", "debug-apk", null, "2026-08-26T00:00:00.000Z") }
    }

    @Test
    fun `toggleExpandHosted keeps the build timestamp of each nested file`() = runTest(dispatcher) {
        val api = mockk<ClServerApi> {
            coEvery { getManifest() } returns Manifest(emptyList(), emptyList())
            coEvery { listHostedFiles("periodical", "builds") } returns
                FileList(listOf(HostedFileEntry("app.apk", "2026-08-26T00:00:00.000Z")))
        }
        val viewModel = viewModel(downloader(), api)

        viewModel.toggleExpandHosted("builds")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(HostedFileEntry("app.apk", "2026-08-26T00:00:00.000Z")),
            viewModel.uiState.value.expandedHostedFiles["builds"],
        )
    }

    @Test
    fun `downloadNestedFile checks listHostedFiles for the current server timestamp before resolving the cache`() =
        runTest(dispatcher) {
            val api = mockk<ClServerApi> {
                coEvery { getManifest() } returns Manifest(emptyList(), emptyList())
                coEvery { listHostedFiles("periodical", "builds") } returns
                    FileList(listOf(HostedFileEntry("app.apk", "2026-08-26T00:00:00.000Z")))
            }
            val downloader = downloader {
                coEvery { resolvePendingInstall("periodical", "app.apk", "2026-08-26T00:00:00.000Z") } returns null
            }
            val viewModel = viewModel(downloader, api)

            viewModel.downloadNestedFile("builds", "app.apk")
            dispatcher.scheduler.advanceUntilIdle()

            coVerify { downloader.startDownload("periodical", "builds", "app.apk", "2026-08-26T00:00:00.000Z") }
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
}
