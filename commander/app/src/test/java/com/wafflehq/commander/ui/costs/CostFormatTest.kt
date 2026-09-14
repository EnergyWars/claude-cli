package com.wafflehq.commander.ui.costs

import org.junit.Assert.assertEquals
import org.junit.Test

class CostFormatTest {

    @Test
    fun `formatUsd formats zero`() {
        assertEquals("$0.0000", formatUsd(0.0))
    }

    @Test
    fun `formatUsd formats a small fraction with four decimals`() {
        assertEquals("$0.0002", formatUsd(0.0002))
    }

    @Test
    fun `formatUsd formats a typical value`() {
        assertEquals("$0.0200", formatUsd(0.02))
    }

    @Test
    fun `formatUsd formats a large value with thousands separators`() {
        assertEquals("$1,234.5000", formatUsd(1234.5))
    }

    @Test
    fun `formatUsd does not crash on a negative value`() {
        assertEquals("$-0.0200", formatUsd(-0.02))
    }

    @Test
    fun `formatTokenCount formats zero`() {
        assertEquals("0", formatTokenCount(0L))
    }

    @Test
    fun `formatTokenCount groups thousands`() {
        assertEquals("1,234,567", formatTokenCount(1_234_567L))
    }

    @Test
    fun `formatTokenCount does not crash on a negative value`() {
        assertEquals("-5", formatTokenCount(-5L))
    }
}
