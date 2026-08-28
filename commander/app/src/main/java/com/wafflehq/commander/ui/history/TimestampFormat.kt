package com.wafflehq.commander.ui.history

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

/** UTC-ISO-8601 ("2026-08-25T19:13:12.620Z") in die Zeitzone des Geräts umgerechnet und im deutschen Format formatiert. */
fun formatTimestamp(iso: String): String {
    val instant = try {
        Instant.parse(iso)
    } catch (error: DateTimeParseException) {
        return try {
            formatter.format(LocalDateTime.parse(iso))
        } catch (error: DateTimeParseException) {
            iso
        }
    }
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}

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
