package com.wafflehq.appgetter.ui.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressFormatTest {

    @Test
    fun `formats speed below one kilobyte per second in bytes`() {
        assertEquals("500 B/s", formatDownloadSpeed(500.0))
    }

    @Test
    fun `formats speed in kilobytes per second`() {
        assertEquals("2.0 KB/s", formatDownloadSpeed(2_048.0))
    }

    @Test
    fun `formats speed in megabytes per second`() {
        assertEquals("1.5 MB/s", formatDownloadSpeed(1_572_864.0))
    }

    @Test
    fun `formats eta under a minute as seconds`() {
        assertEquals("42s", formatDownloadEta(42))
    }

    @Test
    fun `formats eta of a minute or more as minutes and seconds`() {
        assertEquals("1:05", formatDownloadEta(65))
    }

    @Test
    fun `eta is null when unknown`() {
        assertNull(formatDownloadEta(null))
    }
}
