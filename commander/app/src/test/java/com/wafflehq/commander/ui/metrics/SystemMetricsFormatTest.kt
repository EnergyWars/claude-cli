package com.wafflehq.commander.ui.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemMetricsFormatTest {

    @Test
    fun `formats zero bytes`() {
        assertEquals("0.0 GiB", formatBytesAsGiB(0L))
    }

    @Test
    fun `formats a small byte count`() {
        assertEquals("0.0 GiB", formatBytesAsGiB(1_000L))
    }

    @Test
    fun `formats a large byte count`() {
        assertEquals("16.0 GiB", formatBytesAsGiB(16L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formats a fractional GiB value`() {
        assertEquals("1.5 GiB", formatBytesAsGiB(1_536L * 1024 * 1024))
    }
}
