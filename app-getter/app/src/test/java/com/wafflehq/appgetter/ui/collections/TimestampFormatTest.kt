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
        assertEquals("2026-08-25 21:13:12", formatTimestamp("2026-08-25T19:13:12.620Z"))
    }

    @Test
    fun `leaves a timestamp without timezone information unchanged`() {
        assertEquals("2026-08-25 19:13:12", formatTimestamp("2026-08-25T19:13:12"))
    }
}
