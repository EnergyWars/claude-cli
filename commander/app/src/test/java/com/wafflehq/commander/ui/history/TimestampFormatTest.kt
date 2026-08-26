package com.wafflehq.commander.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampFormatTest {

    @Test
    fun `strips the T separator and sub-second precision`() {
        assertEquals("2026-08-25 19:13:12", formatTimestamp("2026-08-25T19:13:12.620Z"))
    }

    @Test
    fun `leaves a timestamp without sub-second precision mostly unchanged`() {
        assertEquals("2026-08-25 19:13:12", formatTimestamp("2026-08-25T19:13:12"))
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
