package com.wafflehq.commander.ui.setup

import androidx.annotation.StringRes
import com.wafflehq.commander.R

const val TEST_PORT = 8787
const val SERVICE_PORT = 7765

private const val MIN_PORT = 1
private const val MAX_PORT = 65535

enum class PortOption(val port: Int?, @param:StringRes val labelRes: Int) {
    Test(TEST_PORT, R.string.setup_port_test),
    Service(SERVICE_PORT, R.string.setup_port_service),
    Custom(null, R.string.setup_port_custom),
}

fun portOptionFor(port: Int): PortOption = PortOption.entries.firstOrNull { it.port == port } ?: PortOption.Custom

fun resolvePort(option: PortOption, customPort: String): Int? =
    option.port ?: customPort.trim().toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }
