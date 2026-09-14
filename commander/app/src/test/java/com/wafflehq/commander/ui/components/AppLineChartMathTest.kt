package com.wafflehq.commander.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLineChartMathTest {

    @Test
    fun `computeChartRange returns a default range for an empty list`() {
        val range = computeChartRange(emptyList())

        assertEquals(0f, range.start, 0f)
        assertEquals(1f, range.endInclusive, 0f)
    }

    @Test
    fun `computeChartRange pads symmetrically around a single point`() {
        val range = computeChartRange(listOf(5f))

        assertEquals(4f, range.start, 0f)
        assertEquals(6f, range.endInclusive, 0f)
    }

    @Test
    fun `computeChartRange pads symmetrically when all values are equal`() {
        val range = computeChartRange(listOf(0f, 0f, 0f))

        assertEquals(-1f, range.start, 0f)
        assertEquals(1f, range.endInclusive, 0f)
    }

    @Test
    fun `computeChartRange adds ten percent headroom around a normal spread`() {
        val range = computeChartRange(listOf(0f, 50f, 100f))

        assertEquals(-10f, range.start, 0f)
        assertEquals(110f, range.endInclusive, 0f)
    }

    @Test
    fun `mapValueToY maps the range start to the bottom of the chart`() {
        val y = mapValueToY(0f, 0f..100f, 200f)

        assertEquals(200f, y, 0f)
    }

    @Test
    fun `mapValueToY maps the range end to the top of the chart`() {
        val y = mapValueToY(100f, 0f..100f, 200f)

        assertEquals(0f, y, 0f)
    }

    @Test
    fun `mapValueToY maps the midpoint to half the chart height`() {
        val y = mapValueToY(50f, 0f..100f, 200f)

        assertEquals(100f, y, 0f)
    }

    @Test
    fun `mapValueToY does not crash on a degenerate zero-width range`() {
        val y = mapValueToY(5f, 5f..5f, 200f)

        assertEquals(100f, y, 0f)
    }
}
