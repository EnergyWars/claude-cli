package com.wafflehq.commander.data.api

import kotlin.math.ceil

private const val SPEED_WINDOW_MILLIS = 2_000L

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Double,
    val etaSeconds: Long?,
)

fun DownloadProgress.fraction(): Float? {
    val total = totalBytes ?: return null
    if (total <= 0L) return null
    return (bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** Tracks recent (elapsedMillis, bytesDownloaded) samples within [windowMillis] to derive a smoothed speed and ETA. */
class DownloadProgressTracker(private val windowMillis: Long = SPEED_WINDOW_MILLIS) {
    private data class Sample(val elapsedMillis: Long, val bytesDownloaded: Long)

    private val samples = ArrayDeque<Sample>()

    fun update(elapsedMillis: Long, bytesDownloaded: Long, totalBytes: Long?): DownloadProgress {
        samples.addLast(Sample(elapsedMillis, bytesDownloaded))
        while (samples.size > 1 && elapsedMillis - samples.first().elapsedMillis > windowMillis) {
            samples.removeFirst()
        }
        val oldest = samples.first()
        val deltaMillis = elapsedMillis - oldest.elapsedMillis
        val deltaBytes = bytesDownloaded - oldest.bytesDownloaded
        val bytesPerSecond = if (deltaMillis > 0) deltaBytes * 1000.0 / deltaMillis else 0.0
        val remainingBytes = totalBytes?.let { (it - bytesDownloaded).coerceAtLeast(0L) }
        val etaSeconds = if (remainingBytes != null && bytesPerSecond > 0.0) {
            ceil(remainingBytes / bytesPerSecond).toLong()
        } else {
            null
        }
        return DownloadProgress(bytesDownloaded, totalBytes, bytesPerSecond, etaSeconds)
    }
}
