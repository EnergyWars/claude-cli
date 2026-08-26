package com.wafflehq.commander.ui.downloads

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.Manifest
import com.wafflehq.commander.data.download.DownloadPhase
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.data.download.HostedFileDownloader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun viewModel(downloader: HostedFileDownloader): DownloadsViewModel {
        val viewModel = DownloadsViewModel(api, downloader, SavedStateHandle(mapOf("pathName" to "periodical")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `downloadingName is set while the download runs and cleared once consumed`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("commander-test", ".apk")
        val downloader = mockk<HostedFileDownloader> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
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
        val downloader = mockk<HostedFileDownloader> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
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
        val downloader = mockk<HostedFileDownloader> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
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
        val downloader = mockk<HostedFileDownloader> {
            every { downloadStatus } returns statusFlow
        }
        val viewModel = viewModel(downloader)

        statusFlow.value = DownloadStatus(DownloadPhase.VERIFYING)

        assertEquals(DownloadPhase.VERIFYING, viewModel.downloadStatus.value?.phase)
    }
}
