package com.wafflehq.commander.data.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class HostHistoryTest {

    @Test
    fun `adds host to empty history`() {
        assertEquals(listOf("192.168.0.10"), applyHostHistory(emptyList(), "192.168.0.10"))
    }

    @Test
    fun `moves known host to front without duplicating it`() {
        val current = listOf("192.168.0.10", "192.168.0.11")
        assertEquals(listOf("192.168.0.11", "192.168.0.10"), applyHostHistory(current, "192.168.0.11"))
    }

    @Test
    fun `treats hostnames case insensitively`() {
        val current = listOf("Server.local", "192.168.0.10")
        assertEquals(listOf("server.local", "192.168.0.10"), applyHostHistory(current, "server.local"))
    }

    @Test
    fun `trims the host before storing it`() {
        assertEquals(listOf("192.168.0.10"), applyHostHistory(emptyList(), "  192.168.0.10  "))
    }

    @Test
    fun `ignores a blank host`() {
        val current = listOf("192.168.0.10")
        assertEquals(current, applyHostHistory(current, "   "))
    }

    @Test
    fun `caps the history at the maximum size`() {
        val current = (1..MAX_HOST_HISTORY).map { "192.168.0.$it" }
        val updated = applyHostHistory(current, "10.0.0.1")
        assertEquals(MAX_HOST_HISTORY, updated.size)
        assertEquals("10.0.0.1", updated.first())
        assertEquals("192.168.0.${MAX_HOST_HISTORY - 1}", updated.last())
    }

    @Test
    fun `encodes and decodes a round trip`() {
        val hosts = listOf("192.168.0.10", "server.local")
        assertEquals(hosts, decodeHostHistory(encodeHostHistory(hosts)))
    }

    @Test
    fun `decodes missing or broken payloads as empty`() {
        assertEquals(emptyList<String>(), decodeHostHistory(null))
        assertEquals(emptyList<String>(), decodeHostHistory(""))
        assertEquals(emptyList<String>(), decodeHostHistory("{not json"))
    }
}
