package com.wafflehq.appgetter.ui.collections

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
}
