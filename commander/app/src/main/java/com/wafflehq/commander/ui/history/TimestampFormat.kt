package com.wafflehq.commander.ui.history

import java.time.Duration
import java.time.Instant

/** ISO-8601 ("2026-08-25T19:13:12.620Z") zu einer kompakten, lesbaren Form ("2026-08-25 19:13:12"). */
fun formatTimestamp(iso: String): String = iso.replace('T', ' ').substringBefore('.')

/** Differenz zwischen zwei ISO-8601-Zeitstempeln als kompakte Dauer ("3s", "1m 05s", "1h 02m"). */
fun formatDuration(createdAt: String, updatedAt: String): String {
    val seconds = Duration.between(Instant.parse(createdAt), Instant.parse(updatedAt)).seconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm %02ds".format(minutes, secs)
        else -> "%ds".format(secs)
    }
}
