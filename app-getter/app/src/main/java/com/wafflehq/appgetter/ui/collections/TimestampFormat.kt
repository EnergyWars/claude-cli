package com.wafflehq.appgetter.ui.collections

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** UTC-ISO-8601 ("2026-08-25T19:13:12.620Z") in die Zeitzone des Geräts umgerechnet und kompakt formatiert. */
fun formatTimestamp(iso: String): String {
    val instant = try {
        Instant.parse(iso)
    } catch (error: DateTimeParseException) {
        return iso.replace('T', ' ').substringBefore('.')
    }
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}
