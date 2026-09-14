package com.wafflehq.commander.ui.metrics

import java.util.Locale

private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0

fun formatBytesAsGiB(bytes: Long): String = String.format(Locale.US, "%.1f GiB", bytes / BYTES_PER_GIB)
