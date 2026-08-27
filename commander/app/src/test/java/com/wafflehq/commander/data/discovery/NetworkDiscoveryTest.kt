package com.wafflehq.commander.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.wafflehq.commander.data.api.ClServerApi
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
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

    @Test
    fun `wifiIpv4Address picks the Wi-Fi network's address, ignoring a concurrently active cellular network`() {
        val cellularNetwork = mockk<Network>()
        val wifiNetwork = mockk<Network>()
        val connectivityManager = mockk<ConnectivityManager> {
            every { allNetworks } returns arrayOf(cellularNetwork, wifiNetwork)
            every { getNetworkCapabilities(cellularNetwork) } returns capabilities(isWifi = false)
            every { getNetworkCapabilities(wifiNetwork) } returns capabilities(isWifi = true)
            every { getLinkProperties(wifiNetwork) } returns linkProperties("192.168.1.42")
        }
        val discovery = NetworkDiscovery(mockk<ClServerApi>(), context(connectivityManager))

        assertEquals("192.168.1.42", discovery.wifiIpv4Address())
    }

    @Test
    fun `wifiIpv4Address returns null when no connected network has the Wi-Fi transport`() {
        val cellularNetwork = mockk<Network>()
        val connectivityManager = mockk<ConnectivityManager> {
            every { allNetworks } returns arrayOf(cellularNetwork)
            every { getNetworkCapabilities(cellularNetwork) } returns capabilities(isWifi = false)
        }
        val discovery = NetworkDiscovery(mockk<ClServerApi>(), context(connectivityManager))

        assertNull(discovery.wifiIpv4Address())
    }

    @Test
    fun `localIpv4Address falls back to interface enumeration when no Wi-Fi network is connected`() {
        val discovery = NetworkDiscovery(mockk<ClServerApi>(), context(connectivityManager = null))

        assertEquals(discovery.firstNonLoopbackIpv4Address(), discovery.localIpv4Address())
    }

    private fun context(connectivityManager: ConnectivityManager?): Context =
        mockk<Context> {
            every { getSystemService(ConnectivityManager::class.java) } returns connectivityManager
        }

    private fun capabilities(isWifi: Boolean): NetworkCapabilities =
        mockk { every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns isWifi }

    private fun linkProperties(ipv4Address: String): LinkProperties =
        mockk { every { linkAddresses } returns listOf(linkAddress(ipv4Address)) }

    private fun linkAddress(ipv4Address: String): LinkAddress =
        mockk { every { address } returns InetAddress.getByName(ipv4Address) }
}
