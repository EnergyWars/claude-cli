package com.wafflehq.commander.data.download

import android.content.Context
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.DownloadProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedFileDownloaderTest {

    private val context = mockk<Context>(relaxed = true)
    private val api = mockk<ClServerApi>()

    private fun downloader(historyRepository: DownloadHistoryRepository = mockk(relaxed = true) {
        every { pendingInstalls } returns flowOf(emptyList())
    }) = HostedFileDownloader(context, api, historyRepository)

    @Test
    fun `recognizes an apk file by its extension`() {
        assertTrue(isApkFileName("app-debug.apk"))
    }

    @Test
    fun `recognizes an apk file regardless of case`() {
        assertTrue(isApkFileName("app-release.APK"))
    }

    @Test
    fun `rejects a file that merely contains apk in its name`() {
        assertFalse(isApkFileName("myapp.apk.txt"))
    }

    @Test
    fun `rejects a file without an extension`() {
        assertFalse(isApkFileName("apk"))
    }

    @Test
    fun `rejects an unrelated extension`() {
        assertFalse(isApkFileName("notes.txt"))
    }

    @Test
    fun `downloadEntry moves through downloading, verifying and installing for an apk`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".apk").apply { writeText("apk-bytes") }
        val instance = downloader()
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } coAnswers {
            assertEquals(DownloadPhase.DOWNLOADING, instance.downloadStatus.value?.phase)
            downloaded
        }

        val result = instance.downloadEntry("path", "name")

        assertEquals(downloaded, result)
        assertEquals(DownloadPhase.INSTALLING, instance.downloadStatus.value?.phase)
    }

    @Test
    fun `downloadEntry reports progress while streaming`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".apk").apply { writeText("apk-bytes") }
        val instance = downloader()
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } coAnswers {
            val onProgress = arg<(DownloadProgress) -> Unit>(3)
            onProgress(DownloadProgress(bytesDownloaded = 50, totalBytes = 100, bytesPerSecond = 500.0, etaSeconds = 1))
            downloaded
        }

        instance.downloadEntry("path", "name")

        assertEquals(50L, instance.downloadStatus.value?.progress?.bytesDownloaded)
    }

    @Test
    fun `downloadFile ends in the opening phase for a non-apk file`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("log output") }
        val instance = downloader()
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        instance.downloadFile("path", "name", "notes.txt")

        assertEquals(DownloadPhase.OPENING, instance.downloadStatus.value?.phase)
    }

    @Test
    fun `a successfully downloaded apk is recorded as a pending install`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".apk").apply { writeText("apk-bytes") }
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns downloaded

        instance.downloadEntry("periodical", "debug-apk")

        coVerify { historyRepository.recordPendingInstall("periodical::debug-apk", downloaded.absolutePath) }
    }

    @Test
    fun `a downloaded non-apk file is not recorded as a pending install`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("log output") }
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        instance.downloadFile("periodical", "logs", "notes.txt")

        coVerify(exactly = 0) { historyRepository.recordPendingInstall(any(), any()) }
    }

    @Test
    fun `resolvePendingInstall returns the cached file when it still exists on disk`() = runBlocking {
        val cached = File.createTempFile("commander-test", ".apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(listOf(PendingInstall("periodical::debug-apk", cached.absolutePath)))
        }
        val instance = downloader(historyRepository)

        val result = instance.resolvePendingInstall("periodical", "debug-apk")

        assertEquals(cached, result)
    }

    @Test
    fun `resolvePendingInstall clears and returns null when the cached file is gone`() = runBlocking {
        val missing = File("/tmp/commander-test-missing.apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(listOf(PendingInstall("periodical::debug-apk", missing.absolutePath)))
        }
        val instance = downloader(historyRepository)

        val result = instance.resolvePendingInstall("periodical", "debug-apk")

        assertNull(result)
        coVerify { historyRepository.clearPendingInstall("periodical::debug-apk") }
    }

    @Test
    fun `resolvePendingInstall returns null without a matching entry`() = runBlocking {
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)

        assertNull(instance.resolvePendingInstall("periodical", "debug-apk"))
    }

    @Test
    fun `deletePendingInstall removes the file and clears the cache entry`() = runBlocking {
        val cached = File.createTempFile("commander-test", ".apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)

        instance.deletePendingInstall("periodical", "debug-apk", cached)

        assertFalse(cached.exists())
        coVerify { historyRepository.clearPendingInstall("periodical::debug-apk") }
    }

    @Test
    fun `an empty downloaded file fails verification`() {
        val emptyFile = File.createTempFile("commander-test", ".apk")
        val instance = downloader()
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns emptyFile

        try {
            runBlocking { instance.downloadEntry("path", "name") }
            org.junit.Assert.fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(DownloadPhase.VERIFYING, instance.downloadStatus.value?.phase)
        }
    }

    @Test
    fun `clearDownloadStatus resets the status to null`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".apk").apply { writeText("apk-bytes") }
        val instance = downloader()
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns downloaded
        instance.downloadEntry("path", "name")

        instance.clearDownloadStatus()

        assertNull(instance.downloadStatus.value)
    }
}
