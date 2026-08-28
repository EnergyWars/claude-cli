package com.wafflehq.commander.ui.history

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TimestampFormatTest {

    private lateinit var originalDefault: TimeZone

    @Before
    fun setUp() {
        originalDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalDefault)
    }

    @Test
    fun `converts a UTC timestamp into the device's local timezone`() {
        assertEquals("25.08.2026 21:13:12", formatTimestamp("2026-08-25T19:13:12.620Z"))
    }

    @Test
    fun `formats a timestamp without timezone information in german format`() {
        assertEquals("25.08.2026 19:13:12", formatTimestamp("2026-08-25T19:13:12"))
    }

    @Test
    fun `returns an unparsable timestamp unchanged`() {
        assertEquals("not-a-timestamp", formatTimestamp("not-a-timestamp"))
    }

    @Test
    fun `formats a duration under a minute as seconds`() {
        assertEquals("3s", formatDuration("2026-08-25T19:13:12.000Z", "2026-08-25T19:13:15.000Z"))
    }

    @Test
    fun `formats a duration under an hour as minutes and seconds`() {
        assertEquals("1m 05s", formatDuration("2026-08-25T19:13:12.000Z", "2026-08-25T19:14:17.000Z"))
    }

    @Test
    fun `formats a duration of an hour or more as hours and minutes`() {
        assertEquals("1h 02m", formatDuration("2026-08-25T19:13:12.000Z", "2026-08-25T20:15:00.000Z"))
    }

    @Test
    fun `treats an identical start and end as zero seconds`() {
        assertEquals("0s", formatDuration("2026-08-25T19:13:12.000Z", "2026-08-25T19:13:12.000Z"))
    }
}
