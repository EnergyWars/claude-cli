package com.wafflehq.appgetter.data.discovery

import com.wafflehq.appgetter.data.api.AppGetterApi
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val SCAN_CONCURRENCY = 32
private const val MAX_SCAN_HOST_BITS = 9

private val PREFERRED_INTERFACE_PREFIXES = listOf("wlan", "eth")
private val EXCLUDED_INTERFACE_PREFIXES = listOf("tun", "ppp", "clat", "ipsec")

/** Races [predicate] over [candidates], returning the first match or null once every candidate has been tried. */
suspend fun <T> raceFirstMatch(
    candidates: List<T>,
    concurrency: Int = SCAN_CONCURRENCY,
    predicate: suspend (T) -> Boolean,
): T? = coroutineScope {
    val result = CompletableDeferred<T?>()
    val semaphore = Semaphore(concurrency)
    val jobs = candidates.map { candidate ->
        launch {
            semaphore.withPermit {
                if (!result.isCompleted && predicate(candidate)) {
                    result.complete(candidate)
                }
            }
        }
    }
    launch {
        jobs.forEach { it.join() }
        result.complete(null)
    }
    val match = result.await()
    jobs.forEach { it.cancel() }
    match
}

/**
 * All host addresses in the subnet that [localIpv4] belongs to, given its [prefixLength].
 * Networks larger than a /23 fall back to the device's own /24 window, keeping the scan bounded.
 */
fun subnetHosts(localIpv4: String, prefixLength: Int = 24): List<String> {
    val hostBits = 32 - prefixLength.coerceIn(0, 32)
    if (hostBits <= 0 || hostBits > MAX_SCAN_HOST_BITS) {
        val base = localIpv4.substringBeforeLast('.')
        return (1..254).map { "$base.$it" }
    }
    val ipValue = ipv4ToLong(localIpv4)
    val networkValue = ipValue and (0xFFFFFFFFL shl hostBits)
    val hostCount = (1L shl hostBits) - 2
    return (1..hostCount).map { offset -> longToIpv4(networkValue + offset) }
}

private fun ipv4ToLong(ip: String): Long =
    ip.split('.').fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }

private fun longToIpv4(value: Long): String =
    listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }

private data class LocalNetwork(val address: String, val prefixLength: Int)

@Singleton
class NetworkDiscovery @Inject constructor(
    private val api: AppGetterApi,
) {
    /** Scans every address in the device's local subnet for a `cl server` on [port], returns the first hit. */
    suspend fun discoverHost(port: Int): String? {
        val network = localNetwork() ?: return null
        return raceFirstMatch(subnetHosts(network.address, network.prefixLength)) { host -> api.probeStatus(host, port) }
    }

    private fun localNetwork(): LocalNetwork? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.filterNot { iface -> EXCLUDED_INTERFACE_PREFIXES.any { iface.name.startsWith(it) } }
            ?.sortedByDescending { iface -> PREFERRED_INTERFACE_PREFIXES.any { iface.name.startsWith(it) } }
            .orEmpty()
        for (iface in interfaces) {
            val match = iface.interfaceAddresses.firstOrNull { addr ->
                addr.address is Inet4Address && !addr.address.isLoopbackAddress
            } ?: continue
            val address = match.address.hostAddress ?: continue
            return LocalNetwork(address, match.networkPrefixLength.toInt())
        }
        return null
    }
}
