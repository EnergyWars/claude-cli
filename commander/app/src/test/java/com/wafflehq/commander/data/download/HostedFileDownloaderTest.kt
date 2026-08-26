package com.wafflehq.commander.data.download

import android.content.Context
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.DownloadProgress
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedFileDownloaderTest {

    private val context = mockk<Context>(relaxed = true)
    private val api = mockk<ClServerApi>()
    private val downloader = HostedFileDownloader(context, api)

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
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } coAnswers {
            assertEquals(DownloadPhase.DOWNLOADING, downloader.downloadStatus.value?.phase)
            val onProgress = arg<(DownloadProgress) -> Unit>(3)
            onProgress(DownloadProgress(bytesDownloaded = 50, totalBytes = 100, bytesPerSecond = 500.0, etaSeconds = 1))
            downloaded
        }

        val result = downloader.downloadEntry("path", "name")

        assertEquals(downloaded, result)
        assertEquals(50L, downloader.downloadStatus.value?.progress?.bytesDownloaded)
        assertEquals(DownloadPhase.INSTALLING, downloader.downloadStatus.value?.phase)
    }

    @Test
    fun `downloadFile ends in the opening phase for a non-apk file`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".txt").apply { writeText("log output") }
        coEvery { api.downloadHostedFile(any(), any(), any(), any(), any()) } returns downloaded

        downloader.downloadFile("path", "name", "notes.txt")

        assertEquals(DownloadPhase.OPENING, downloader.downloadStatus.value?.phase)
    }

    @Test
    fun `an empty downloaded file fails verification`() {
        val emptyFile = File.createTempFile("commander-test", ".apk")
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns emptyFile

        try {
            runBlocking { downloader.downloadEntry("path", "name") }
            org.junit.Assert.fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(DownloadPhase.VERIFYING, downloader.downloadStatus.value?.phase)
        }
    }

    @Test
    fun `clearDownloadStatus resets the status to null`() = runBlocking {
        val downloaded = File.createTempFile("commander-test", ".apk").apply { writeText("apk-bytes") }
        coEvery { api.downloadHostedEntry(any(), any(), any(), any()) } returns downloaded
        downloader.downloadEntry("path", "name")

        downloader.clearDownloadStatus()

        assertNull(downloader.downloadStatus.value)
    }
}
