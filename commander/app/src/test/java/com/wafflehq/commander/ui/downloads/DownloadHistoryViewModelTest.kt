package com.wafflehq.commander.ui.downloads

import androidx.lifecycle.SavedStateHandle
import com.wafflehq.commander.data.download.DownloadHistoryRepository
import com.wafflehq.commander.data.download.DownloadVersion
import com.wafflehq.commander.data.download.HostedFileDownloader
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadHistoryViewModelPureTest {

    @Test
    fun `groupDownloadHistory keeps only entries for the given path, newest first`() {
        val v1 = DownloadVersion("periodical::debug-apk", "ts1", "2026-08-25T00:00:00Z", "/tmp/app-1.apk")
        val v2 = DownloadVersion("periodical::debug-apk", "ts2", "2026-08-26T00:00:00Z", "/tmp/app-2.apk")
        val other = DownloadVersion("other::debug-apk", "ts1", "2026-08-25T00:00:00Z", "/tmp/other.apk")

        val groups = groupDownloadHistory(listOf(v1, v2, other), "periodical")

        assertEquals(listOf(DownloadHistoryGroup("debug-apk", listOf(v2, v1))), groups)
    }

    @Test
    fun `groupDownloadHistory groups multiple identities and sorts them by name`() {
        val notes = DownloadVersion("periodical::notes.txt", null, "2026-08-25T00:00:00Z", "/tmp/notes.txt")
        val apk = DownloadVersion("periodical::debug-apk", "ts", "2026-08-25T00:00:00Z", "/tmp/app.apk")

        val groups = groupDownloadHistory(listOf(notes, apk), "periodical")

        assertEquals(listOf("debug-apk", "notes.txt"), groups.map { it.identity })
    }

    @Test
    fun `groupDownloadHistory returns an empty list without matching entries`() {
        val other = DownloadVersion("other::debug-apk", "ts1", "2026-08-25T00:00:00Z", "/tmp/other.apk")

        assertTrue(groupDownloadHistory(listOf(other), "periodical").isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        versions: List<DownloadVersion> = emptyList(),
        downloader: HostedFileDownloader = mockk(relaxed = true),
    ): DownloadHistoryViewModel {
        val historyRepository = mockk<DownloadHistoryRepository>()
        every { historyRepository.versions } returns flowOf(versions)
        val viewModel = DownloadHistoryViewModel(historyRepository, downloader, SavedStateHandle(mapOf("pathName" to "periodical")))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `groups reflects only versions for the current path`() = runTest(dispatcher) {
        val version = DownloadVersion("periodical::debug-apk", "ts", "2026-08-26T00:00:00Z", "/tmp/app.apk")
        val other = DownloadVersion("other::debug-apk", "ts", "2026-08-26T00:00:00Z", "/tmp/other.apk")

        val viewModel = viewModel(versions = listOf(version, other))

        assertEquals(listOf(DownloadHistoryGroup("debug-apk", listOf(version))), viewModel.groups.value)
    }

    @Test
    fun `delete forwards to the downloader with the identity derived from the key`() = runTest(dispatcher) {
        val downloader = mockk<HostedFileDownloader>(relaxed = true)
        val version = DownloadVersion("periodical::debug-apk", "ts", "2026-08-26T00:00:00Z", "/tmp/app.apk")
        val viewModel = viewModel(versions = listOf(version), downloader = downloader)

        viewModel.delete("debug-apk", version)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { downloader.deletePendingInstall("periodical", "debug-apk", File("/tmp/app.apk")) }
    }

    @Test
    fun `openOrInstallIntent delegates to the downloader for the version's file`() {
        val downloader = mockk<HostedFileDownloader>(relaxed = true)
        val viewModel = viewModel(downloader = downloader)
        val version = DownloadVersion("periodical::debug-apk", "ts", "2026-08-26T00:00:00Z", "/tmp/app.apk")

        viewModel.openOrInstallIntent(version)

        verify { downloader.openOrInstallIntent(File("/tmp/app.apk")) }
    }
}
