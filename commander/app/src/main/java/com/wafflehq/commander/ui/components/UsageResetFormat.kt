package com.wafflehq.commander.ui.components

import com.wafflehq.commander.data.api.UsageLimit
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val USAGE_RESET_PATTERN = Regex(
    "^([A-Za-z]{3,})\\s+(\\d{1,2}),\\s+(\\d{1,2})(?::(\\d{2}))?(am|pm)\\s*\\(([^)]+)\\)$",
    RegexOption.IGNORE_CASE,
)

private val MONTH_ABBREVIATIONS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)

private val CLOCK_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

private const val WEEKLY_LABEL_PREFIX = "current week"
private const val WEEKLY_WINDOW_HOURS = 7 * 24L
private const val MIN_ELAPSED_HOURS_FOR_PACE = 0.1

data class UsageCountdown(val hours: Int, val minutes: Int)

data class UsagePace(val paceRatioPercent: Int, val elapsedHours: Double)

fun parseUsageResetAt(resetsAt: String, referenceInstant: Instant = Instant.now()): ZonedDateTime? {
    val match = USAGE_RESET_PATTERN.matchEntire(resetsAt.trim()) ?: return null
    val groups = match.groupValues
    val month = MONTH_ABBREVIATIONS[groups[1].take(3).lowercase()] ?: return null
    val day = groups[2].toIntOrNull() ?: return null
    val hour12 = groups[3].toIntOrNull() ?: return null
    val minute = if (groups[4].isEmpty()) 0 else groups[4].toIntOrNull() ?: return null
    if (hour12 !in 1..12 || minute !in 0..59 || day !in 1..31) return null
    val isPm = groups[5].equals("pm", ignoreCase = true)
    val hour24 = when {
        hour12 == 12 && !isPm -> 0
        hour12 == 12 && isPm -> 12
        isPm -> hour12 + 12
        else -> hour12
    }
    val zone = try {
        ZoneId.of(groups[6].trim())
    } catch (e: DateTimeException) {
        return null
    }
    val zonedNow = ZonedDateTime.ofInstant(referenceInstant, zone)
    fun candidateFor(year: Int): ZonedDateTime? = try {
        ZonedDateTime.of(year, month, day, hour24, minute, 0, 0, zone)
    } catch (e: DateTimeException) {
        null
    }
    val candidate = candidateFor(zonedNow.year) ?: return null
    return if (candidate.isBefore(zonedNow)) candidateFor(zonedNow.year + 1) ?: candidate else candidate
}

fun formatUsageResetClockTime(resetAt: ZonedDateTime, zone: ZoneId = ZoneId.systemDefault()): String =
    resetAt.withZoneSameInstant(zone).format(CLOCK_TIME_FORMATTER)

fun formatClockTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    ZonedDateTime.ofInstant(instant, zone).format(CLOCK_TIME_FORMATTER)

fun usageResetCountdown(resetAt: ZonedDateTime, now: Instant = Instant.now()): UsageCountdown {
    val totalMinutes = Duration.between(now, resetAt.toInstant()).toMinutes().coerceAtLeast(0)
    return UsageCountdown(hours = (totalMinutes / 60).toInt(), minutes = (totalMinutes % 60).toInt())
}

fun isWeeklyUsageLimit(label: String): Boolean = label.trim().lowercase().startsWith(WEEKLY_LABEL_PREFIX)

fun computeWeeklyUsagePace(limit: UsageLimit, resetAt: ZonedDateTime, now: Instant = Instant.now()): UsagePace? {
    if (!isWeeklyUsageLimit(limit.label)) return null
    val remainingHours = Duration.between(now, resetAt.toInstant()).toMillis() / 3_600_000.0
    val elapsedHours = WEEKLY_WINDOW_HOURS - remainingHours
    if (elapsedHours < MIN_ELAPSED_HOURS_FOR_PACE) return null
    val expectedPercent = 100.0 * elapsedHours / WEEKLY_WINDOW_HOURS
    val paceRatioPercent = (limit.percentUsed / expectedPercent * 100).roundToInt()
    return UsagePace(paceRatioPercent, elapsedHours)
}
