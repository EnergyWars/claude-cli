package com.wafflehq.appgetter.data.discovery

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkDiscoveryTest {

    @Test
    fun `subnetHosts lists all 254 addresses of the -24 subnet`() {
        val hosts = subnetHosts("192.168.1.42")

        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `subnetHosts respects a smaller prefix length such as -28`() {
        val hosts = subnetHosts("10.0.0.20", prefixLength = 28)

        assertEquals(14, hosts.size)
        assertEquals("10.0.0.17", hosts.first())
        assertEquals("10.0.0.30", hosts.last())
    }

    @Test
    fun `subnetHosts falls back to the device -24 window for very large networks`() {
        val hosts = subnetHosts("10.1.2.3", prefixLength = 8)

        assertEquals(254, hosts.size)
        assertEquals("10.1.2.1", hosts.first())
        assertEquals("10.1.2.254", hosts.last())
    }

    @Test
    fun `raceFirstMatch returns the first candidate for which the predicate is true`() = runBlocking {
        val result = raceFirstMatch((1..20).toList()) { candidate -> candidate == 7 }

        assertEquals(7, result)
    }

    @Test
    fun `raceFirstMatch returns null once every candidate has been tried without a match`() = runBlocking {
        val result = raceFirstMatch((1..20).toList()) { false }

        assertNull(result)
    }

    @Test
    fun `raceFirstMatch does not evaluate every candidate once a match is found`() = runBlocking {
        val evaluated = AtomicInteger(0)

        raceFirstMatch((1..100).toList(), concurrency = 1) { candidate ->
            evaluated.incrementAndGet()
            candidate == 1
        }

        assertEquals(1, evaluated.get())
    }
}
