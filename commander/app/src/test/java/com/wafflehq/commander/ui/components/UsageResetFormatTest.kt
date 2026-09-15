package com.wafflehq.commander.ui.components

import com.wafflehq.commander.data.api.UsageLimit
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageResetFormatTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `parses a reset time with minutes`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = parseUsageResetAt("Aug 27, 5:40pm (Europe/Berlin)", now)
        assertEquals(17, resetAt?.hour)
        assertEquals(40, resetAt?.minute)
        assertEquals(27, resetAt?.dayOfMonth)
        assertEquals(8, resetAt?.monthValue)
        assertEquals(berlin, resetAt?.zone)
    }

    @Test
    fun `parses a reset time without minutes`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = parseUsageResetAt("Aug 29, 9pm (Europe/Berlin)", now)
        assertEquals(21, resetAt?.hour)
        assertEquals(0, resetAt?.minute)
    }

    @Test
    fun `treats 12am as midnight and 12pm as noon`() {
        val now = Instant.parse("2026-08-27T00:00:00Z")
        assertEquals(0, parseUsageResetAt("Aug 27, 12am (Europe/Berlin)", now)?.hour)
        assertEquals(12, parseUsageResetAt("Aug 27, 12pm (Europe/Berlin)", now)?.hour)
    }

    @Test
    fun `rolls over to the next year when the date has already passed this year`() {
        val now = Instant.parse("2026-12-30T10:00:00Z")
        val resetAt = parseUsageResetAt("Jan 2, 9am (Europe/Berlin)", now)
        assertEquals(2027, resetAt?.year)
    }

    @Test
    fun `returns null for an unparsable reset string`() {
        assertNull(parseUsageResetAt("resets soon"))
        assertNull(parseUsageResetAt("x"))
    }

    @Test
    fun `returns null for an unknown timezone`() {
        assertNull(parseUsageResetAt("Aug 27, 5:40pm (Not/A_Zone)"))
    }

    @Test
    fun `formats the clock time in a given timezone as 24h HH-mm`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = requireNotNull(parseUsageResetAt("Aug 27, 5:40pm (Europe/Berlin)", now))
        assertEquals("17:40", formatUsageResetClockTime(resetAt, berlin))
        assertEquals("15:40", formatUsageResetClockTime(resetAt, ZoneId.of("UTC")))
    }

    @Test
    fun `formats an instant in a given timezone as 24h HH-mm`() {
        val instant = Instant.parse("2026-08-27T15:40:00Z")
        assertEquals("17:40", formatClockTime(instant, berlin))
        assertEquals("15:40", formatClockTime(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `computes the remaining hours and minutes until reset`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = requireNotNull(parseUsageResetAt("Aug 27, 5:40pm (Europe/Berlin)", now))
        val countdown = usageResetCountdown(resetAt, now)
        assertEquals(5, countdown.hours)
        assertEquals(40, countdown.minutes)
    }

    @Test
    fun `clamps a reset time in the past to zero remaining`() {
        val now = Instant.parse("2026-08-27T20:00:00Z")
        val resetAt = requireNotNull(parseUsageResetAt("Aug 27, 5:40pm (Europe/Berlin)", now))
        val countdown = usageResetCountdown(resetAt, now)
        assertEquals(0, countdown.hours)
        assertEquals(0, countdown.minutes)
    }

    @Test
    fun `recognizes weekly labels regardless of the model suffix`() {
        assertEquals(true, isWeeklyUsageLimit("Current week (all models)"))
        assertEquals(true, isWeeklyUsageLimit("Current week (Fable)"))
        assertEquals(true, isWeeklyUsageLimit("current WEEK"))
        assertEquals(false, isWeeklyUsageLimit("Current session"))
    }

    @Test
    fun `computes usage pace for a weekly limit`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = now.plusSeconds(97 * 3600L)
        val limit = UsageLimit(label = "Current week (all models)", percentUsed = 52, resetsAt = "ignored")
        val pace = requireNotNull(
            computeWeeklyUsagePace(
                limit,
                java.time.ZonedDateTime.ofInstant(resetAt, berlin),
                now,
            ),
        )
        assertEquals(71.0, pace.elapsedHours, 0.01)
        assertEquals(123, pace.paceRatioPercent)
    }

    @Test
    fun `returns null pace for a non-weekly limit`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = now.plusSeconds(3 * 3600L)
        val limit = UsageLimit(label = "Current session", percentUsed = 42, resetsAt = "ignored")
        assertNull(computeWeeklyUsagePace(limit, java.time.ZonedDateTime.ofInstant(resetAt, berlin), now))
    }

    @Test
    fun `returns null pace right after the window resets to avoid a division blowup`() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val resetAt = now.plusSeconds(167 * 3600L + 3500L)
        val limit = UsageLimit(label = "Current week (all models)", percentUsed = 1, resetsAt = "ignored")
        assertNull(computeWeeklyUsagePace(limit, java.time.ZonedDateTime.ofInstant(resetAt, berlin), now))
    }
}
