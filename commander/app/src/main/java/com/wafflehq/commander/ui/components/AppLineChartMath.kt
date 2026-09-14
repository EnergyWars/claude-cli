package com.wafflehq.commander.ui.components

private const val RANGE_PADDING_FRACTION = 0.1f
private const val DEGENERATE_RANGE_PADDING = 1f

fun computeChartRange(values: List<Float>): ClosedFloatingPointRange<Float> {
    if (values.isEmpty()) return 0f..1f
    val min = values.min()
    val max = values.max()
    if (min == max) return (min - DEGENERATE_RANGE_PADDING)..(max + DEGENERATE_RANGE_PADDING)
    val padding = (max - min) * RANGE_PADDING_FRACTION
    return (min - padding)..(max + padding)
}

fun mapValueToY(value: Float, range: ClosedFloatingPointRange<Float>, heightPx: Float): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return heightPx / 2f
    val fraction = (value - range.start) / span
    return heightPx * (1f - fraction)
}
