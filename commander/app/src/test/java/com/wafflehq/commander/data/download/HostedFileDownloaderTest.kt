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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        val result = instance.downloadEntry("path", "name", timestamp = null)

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

        instance.downloadEntry("path", "name", timestamp = null)

        assertEquals(50L, instance.downloadStatus.value?.progress?.bytesDownloaded)
    }

    @Test
    fun `downloadFile ends in the opening phase for a non-apk file`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("log output") }
        val instance = downloader()
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        instance.downloadFile("path", "name", "notes.txt", timestamp = null)

        assertEquals(DownloadPhase.OPENING, instance.downloadStatus.value?.phase)
    }

    @Test
    fun `a successfully downloaded apk is recorded as a pending install with its server timestamp`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".apk").apply { writeText("apk-bytes") }
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns downloaded

        instance.downloadEntry("periodical", "debug-apk", timestamp = "2026-08-26T00:00:00.000Z")

        coVerify {
            historyRepository.recordPendingInstall("periodical::debug-apk", "2026-08-26T00:00:00.000Z", downloaded.absolutePath)
        }
    }

    @Test
    fun `a downloaded non-apk file is not recorded as a pending install`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("log output") }
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        instance.downloadFile("periodical", "logs", "notes.txt", timestamp = null)

        coVerify(exactly = 0) { historyRepository.recordPendingInstall(any(), any(), any()) }
    }

    @Test
    fun `resolvePendingInstall returns the cached file when its timestamp still matches the server`() = runBlocking {
        val cached = File.createTempFile("commander-test", ".apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns
                flowOf(listOf(PendingInstall("periodical::debug-apk", "2026-08-26T00:00:00.000Z", cached.absolutePath)))
        }
        val instance = downloader(historyRepository)

        val result = instance.resolvePendingInstall("periodical", "debug-apk", currentTimestamp = "2026-08-26T00:00:00.000Z")

        assertEquals(cached, result)
    }

    @Test
    fun `resolvePendingInstall deletes a stale cached apk and returns null when the server timestamp changed`() = runBlocking {
        val cached = File.createTempFile("commander-test", ".apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns
                flowOf(listOf(PendingInstall("periodical::debug-apk", "2026-08-26T00:00:00.000Z", cached.absolutePath)))
        }
        val instance = downloader(historyRepository)

        val result = instance.resolvePendingInstall("periodical", "debug-apk", currentTimestamp = "2026-08-27T00:00:00.000Z")

        assertNull(result)
        assertFalse(cached.exists())
        coVerify { historyRepository.clearPendingInstall("periodical::debug-apk") }
    }

    @Test
    fun `resolvePendingInstall keeps the cached file when the current timestamp is unknown`() = runBlocking {
        val cached = File.createTempFile("commander-test", ".apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns
                flowOf(listOf(PendingInstall("periodical::debug-apk", "2026-08-26T00:00:00.000Z", cached.absolutePath)))
        }
        val instance = downloader(historyRepository)

        val result = instance.resolvePendingInstall("periodical", "debug-apk", currentTimestamp = null)

        assertEquals(cached, result)
    }

    @Test
    fun `resolvePendingInstall clears and returns null when the cached file is gone`() = runBlocking {
        val missing = File("/tmp/commander-test-missing.apk")
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns
                flowOf(listOf(PendingInstall("periodical::debug-apk", "2026-08-26T00:00:00.000Z", missing.absolutePath)))
        }
        val instance = downloader(historyRepository)

        val result = instance.resolvePendingInstall("periodical", "debug-apk", currentTimestamp = "2026-08-26T00:00:00.000Z")

        assertNull(result)
        coVerify { historyRepository.clearPendingInstall("periodical::debug-apk") }
    }

    @Test
    fun `resolvePendingInstall returns null without a matching entry`() = runBlocking {
        val historyRepository = mockk<DownloadHistoryRepository>(relaxed = true) {
            every { pendingInstalls } returns flowOf(emptyList())
        }
        val instance = downloader(historyRepository)

        assertNull(instance.resolvePendingInstall("periodical", "debug-apk", currentTimestamp = null))
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
            runBlocking { instance.downloadEntry("path", "name", timestamp = null) }
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
        instance.downloadEntry("path", "name", timestamp = null)

        instance.clearDownloadStatus()

        assertNull(instance.downloadStatus.value)
    }

    @Test
    fun `startDownload sets the active target immediately and returns true`() = runBlocking {
        val instance = downloader()
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("x") }
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        val started = instance.startDownload("periodical", "logs", fileName = "notes.txt", timestamp = null)

        assertTrue(started)
        assertEquals("logs", instance.activeTarget.value?.hostedName)
        assertEquals("notes.txt", instance.activeTarget.value?.identity)
    }

    @Test
    fun `startDownload returns false while another download is already running`() = runBlocking {
        val instance = downloader()
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns
            File.createTempFile("commander-test", ".txt").apply { writeText("x") }

        val first = instance.startDownload("periodical", "debug-apk", fileName = null, timestamp = null)
        val second = instance.startDownload("periodical", "other", fileName = null, timestamp = null)

        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `startDownload emits a success outcome and clears the active target when done`() = runBlocking {
        val instance = downloader()
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("x") }
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        instance.startDownload("periodical", "logs", fileName = "notes.txt", timestamp = null)
        awaitDownloadFinished(instance)

        val outcome = instance.downloadOutcome.value
        assertTrue(outcome is DownloadOutcome.Success)
        assertEquals(downloaded, (outcome as DownloadOutcome.Success).file)
    }

    @Test
    fun `startDownload emits a failure outcome when the download throws`() = runBlocking {
        val instance = downloader()
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } throws ApiException(500, "Download fehlgeschlagen.")

        instance.startDownload("periodical", "debug-apk", fileName = null, timestamp = null)
        awaitDownloadFinished(instance)

        val outcome = instance.downloadOutcome.value
        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals("Download fehlgeschlagen.", (outcome as DownloadOutcome.Failure).message)
    }

    @Test
    fun `consumeDownloadOutcome clears the outcome`() {
        val instance = downloader()

        instance.consumeDownloadOutcome()

        assertNull(instance.downloadOutcome.value)
    }

    private suspend fun awaitDownloadFinished(instance: HostedFileDownloader) {
        withTimeout(5_000) {
            while (instance.activeTarget.value != null) delay(10)
        }
    }
}
