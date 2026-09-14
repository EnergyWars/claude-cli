package com.wafflehq.commander.ui.costs

import java.util.Locale

fun formatUsd(value: Double): String = String.format(Locale.US, "$%,.4f", value)

fun formatTokenCount(value: Long): String = String.format(Locale.US, "%,d", value)
