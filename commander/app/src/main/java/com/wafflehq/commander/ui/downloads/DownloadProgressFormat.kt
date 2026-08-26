package com.wafflehq.commander.ui.downloads

import java.util.Locale

fun formatDownloadSpeed(bytesPerSecond: Double): String = when {
    bytesPerSecond < 1_024.0 -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
    bytesPerSecond < 1_024.0 * 1_024.0 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1_024.0)
    else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1_024.0 * 1_024.0))
}

fun formatDownloadEta(etaSeconds: Long?): String? {
    val seconds = etaSeconds ?: return null
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) {
        String.format(Locale.US, "%d:%02d", minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%ds", remainingSeconds)
    }
}
