package com.wafflehq.appgetter.ui.collections

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
