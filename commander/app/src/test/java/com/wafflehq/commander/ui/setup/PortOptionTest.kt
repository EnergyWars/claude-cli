package com.wafflehq.commander.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortOptionTest {

    @Test
    fun `fixed options carry their port`() {
        assertEquals(8787, PortOption.Test.port)
        assertEquals(7765, PortOption.Service.port)
        assertNull(PortOption.Custom.port)
    }

    @Test
    fun `maps a port back to its fixed option`() {
        assertEquals(PortOption.Test, portOptionFor(TEST_PORT))
        assertEquals(PortOption.Service, portOptionFor(SERVICE_PORT))
        assertEquals(PortOption.Custom, portOptionFor(9000))
    }

    @Test
    fun `fixed options ignore the custom input`() {
        assertEquals(TEST_PORT, resolvePort(PortOption.Test, "9000"))
        assertEquals(SERVICE_PORT, resolvePort(PortOption.Service, ""))
    }

    @Test
    fun `custom option reads the entered port`() {
        assertEquals(9000, resolvePort(PortOption.Custom, " 9000 "))
    }

    @Test
    fun `custom option rejects empty and out of range ports`() {
        assertNull(resolvePort(PortOption.Custom, ""))
        assertNull(resolvePort(PortOption.Custom, "0"))
        assertNull(resolvePort(PortOption.Custom, "65536"))
    }
}
