package com.wafflehq.commander.ui.downloads

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.download.DownloadPhase
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.HostedFileDownloader
import com.wafflehq.commander.data.download.PendingInstall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
        block: HostedFileDownloader.() -> Unit = {},
    ): HostedFileDownloader = mockk(relaxed = true) {
        every { downloadStatus } returns MutableStateFlow(null)
        every { pendingInstalls } returns flowOf(cachedInstalls)
        block()
    }

    private fun viewModel(downloader: HostedFileDownloader): DownloadsViewModel {
        val viewModel = DownloadsViewModel(api, downloader, SavedStateHandle(mapOf("pathName" to "periodical")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `downloadingName is set while the download runs and cleared once consumed`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk") } returns null
            coEvery { downloadEntry("periodical", "debug-apk") } coAnswers {
                delay(1_000)
                downloaded
            }
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.runCurrent()
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(downloaded, viewModel.uiState.value.downloadedFile)
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)

        viewModel.consumeDownloadedFile()
        assertNull(viewModel.uiState.value.downloadingName)
        assertNull(viewModel.uiState.value.downloadedFile)
    }

    @Test
    fun `a failed download clears downloadingName and reports the error`() = runTest(dispatcher) {
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk") } returns null
            coEvery { downloadEntry("periodical", "debug-apk") } throws ApiException(500, "Download fehlgeschlagen.")
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadingName)
        assertEquals("Download fehlgeschlagen.", viewModel.uiState.value.error)
    }

    @Test
    fun `a second download while one is running is ignored`() = runTest(dispatcher) {
        var callCount = 0
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk") } returns null
            coEvery { downloadEntry("periodical", "debug-apk") } coAnswers {
                callCount++
                delay(1_000)
                File.createTempFile("commander-test", ".apk")
            }
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.runCurrent()
        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, callCount)
    }

    @Test
    fun `downloadStatus passes through the downloader's status flow`() = runTest(dispatcher) {
        val statusFlow = MutableStateFlow<DownloadStatus?>(null)
        val downloader = mockk<HostedFileDownloader>(relaxed = true) {
            every { downloadStatus } returns statusFlow
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val viewModel = viewModel(downloader)

        statusFlow.value = DownloadStatus(DownloadPhase.VERIFYING)

        assertEquals(DownloadPhase.VERIFYING, viewModel.downloadStatus.value?.phase)
    }

    @Test
    fun `pendingInstalls exposes cached identities for the current path only`() = runTest(dispatcher) {
        val downloader = downloader(
            cachedInstalls = listOf(
                PendingInstall("periodical::debug-apk", "/tmp/app-debug.apk"),
                PendingInstall("other-project::debug-apk", "/tmp/other.apk"),
            ),
        )
        val viewModel = viewModel(downloader)

        assertEquals(setOf("debug-apk"), viewModel.pendingInstalls.value)
    }

    @Test
    fun `tapping download again reuses a cached pending install without redownloading`() = runTest(dispatcher) {
        val cached = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk") } returns cached
            coEvery { downloadEntry(any(), any()) } throws AssertionError("should not redownload a cached file")
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(cached, viewModel.uiState.value.downloadedFile)
        assertEquals("debug-apk", viewModel.uiState.value.downloadingName)
    }

    @Test
    fun `deleteDownloadedFile removes the file from disk and clears the dialog state`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val downloader = downloader {
            coEvery { resolvePendingInstall("periodical", "debug-apk") } returns null
            coEvery { downloadEntry("periodical", "debug-apk") } returns downloaded
        }
        val viewModel = viewModel(downloader)

        viewModel.downloadEntry("debug-apk")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(downloaded, viewModel.uiState.value.downloadedFile)

        viewModel.deleteDownloadedFile()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadedFile)
        assertNull(viewModel.uiState.value.downloadingName)
        coVerify { downloader.deletePendingInstall("periodical", "debug-apk", downloaded) }
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
