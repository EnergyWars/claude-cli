package com.wafflehq.appgetter.data.install

import android.content.Context
import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.api.DownloadProgress
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkInstallerTest {

    private val context = mockk<Context>(relaxed = true)
    private val api = mockk<AppGetterApi>()
    private val installer = ApkInstaller(context, api)

    @Test
    fun `downloadFile moves through downloading, verifying and installing`() = runBlocking {
        val downloaded = File.createTempFile("appgetter-test", ".apk").apply { writeText("apk-bytes") }
        coEvery { api.downloadCollectionFile(any(), any(), any(), any(), any()) } coAnswers {
            assertEquals(DownloadPhase.DOWNLOADING, installer.downloadStatus.value?.phase)
            val onProgress = arg<(DownloadProgress) -> Unit>(4)
            onProgress(DownloadProgress(bytesDownloaded = 50, totalBytes = 100, bytesPerSecond = 500.0, etaSeconds = 1))
            downloaded
        }

        val result = installer.downloadFile("host", 8787, "test.apk")

        assertEquals(downloaded, result)
        assertEquals(50L, installer.downloadStatus.value?.progress?.bytesDownloaded)
        assertEquals(DownloadPhase.INSTALLING, installer.downloadStatus.value?.phase)
    }

    @Test
    fun `an empty downloaded file fails verification`() {
        val emptyFile = File.createTempFile("appgetter-test", ".apk")
        coEvery { api.downloadCollectionFile(any(), any(), any(), any(), any()) } returns emptyFile

        try {
            runBlocking { installer.downloadFile("host", 8787, "test.apk") }
            org.junit.Assert.fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(DownloadPhase.VERIFYING, installer.downloadStatus.value?.phase)
        }
    }

    @Test
    fun `clearDownloadStatus resets the status to null`() = runBlocking {
        val downloaded = File.createTempFile("appgetter-test", ".apk").apply { writeText("apk-bytes") }
        coEvery { api.downloadCollectionFile(any(), any(), any(), any(), any()) } returns downloaded
        installer.downloadFile("host", 8787, "test.apk")

        installer.clearDownloadStatus()

        assertNull(installer.downloadStatus.value)
    }
}
