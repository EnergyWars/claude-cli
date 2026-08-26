package com.wafflehq.commander.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `first sample has no speed or eta yet`() {
        val tracker = DownloadProgressTracker()

        val progress = tracker.update(elapsedMillis = 0, bytesDownloaded = 0, totalBytes = 1_000)

        assertEquals(0.0, progress.bytesPerSecond, 0.0)
        assertNull(progress.etaSeconds)
    }

    @Test
    fun `computes speed from bytes transferred over elapsed time`() {
        val tracker = DownloadProgressTracker()
        tracker.update(elapsedMillis = 0, bytesDownloaded = 0, totalBytes = null)

        val progress = tracker.update(elapsedMillis = 1_000, bytesDownloaded = 500_000, totalBytes = null)

        assertEquals(500_000.0, progress.bytesPerSecond, 0.001)
    }

    @Test
    fun `computes eta from remaining bytes and current speed`() {
        val tracker = DownloadProgressTracker()
        tracker.update(elapsedMillis = 0, bytesDownloaded = 0, totalBytes = 1_000_000)

        val progress = tracker.update(elapsedMillis = 1_000, bytesDownloaded = 250_000, totalBytes = 1_000_000)

        assertEquals(250_000.0, progress.bytesPerSecond, 0.001)
        assertEquals(3L, progress.etaSeconds)
    }

    @Test
    fun `eta is null when total size is unknown`() {
        val tracker = DownloadProgressTracker()
        tracker.update(elapsedMillis = 0, bytesDownloaded = 0, totalBytes = null)

        val progress = tracker.update(elapsedMillis = 1_000, bytesDownloaded = 250_000, totalBytes = null)

        assertNull(progress.etaSeconds)
    }

    @Test
    fun `eta is null while speed is still zero`() {
        val tracker = DownloadProgressTracker()

        val progress = tracker.update(elapsedMillis = 0, bytesDownloaded = 0, totalBytes = 1_000)

        assertEquals(0.0, progress.bytesPerSecond, 0.0)
        assertNull(progress.etaSeconds)
    }

    @Test
    fun `evicts samples older than the speed window to reflect only recent throughput`() {
        val tracker = DownloadProgressTracker(windowMillis = 2_000)
        tracker.update(elapsedMillis = 0, bytesDownloaded = 0, totalBytes = null)
        tracker.update(elapsedMillis = 1_000, bytesDownloaded = 500, totalBytes = null)

        val progress = tracker.update(elapsedMillis = 3_000, bytesDownloaded = 1_500, totalBytes = null)

        assertEquals(500.0, progress.bytesPerSecond, 0.001)
    }

    @Test
    fun `reports the exact bytes and total passed in`() {
        val tracker = DownloadProgressTracker()

        val progress = tracker.update(elapsedMillis = 500, bytesDownloaded = 42, totalBytes = 100)

        assertEquals(42L, progress.bytesDownloaded)
        assertEquals(100L, progress.totalBytes)
    }

    @Test
    fun `fraction is the ratio of downloaded to total bytes`() {
        val progress = DownloadProgress(bytesDownloaded = 50, totalBytes = 100, bytesPerSecond = 10.0, etaSeconds = 5)

        assertEquals(0.5f, progress.fraction())
    }

    @Test
    fun `fraction is null when total bytes is unknown`() {
        val progress = DownloadProgress(bytesDownloaded = 50, totalBytes = null, bytesPerSecond = 10.0, etaSeconds = null)

        assertNull(progress.fraction())
    }

    @Test
    fun `fraction is null when total bytes is zero`() {
        val progress = DownloadProgress(bytesDownloaded = 0, totalBytes = 0, bytesPerSecond = 0.0, etaSeconds = null)

        assertNull(progress.fraction())
    }

    @Test
    fun `fraction is clamped to 1 when more bytes than total were reported`() {
        val progress = DownloadProgress(bytesDownloaded = 150, totalBytes = 100, bytesPerSecond = 10.0, etaSeconds = 0)

        assertEquals(1f, progress.fraction())
        assertTrue(progress.fraction()!! <= 1f)
    }
}
