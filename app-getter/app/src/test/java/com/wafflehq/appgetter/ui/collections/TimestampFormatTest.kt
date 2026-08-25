package com.wafflehq.appgetter.ui.collections

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
}
