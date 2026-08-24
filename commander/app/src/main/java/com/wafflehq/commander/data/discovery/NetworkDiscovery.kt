package com.wafflehq.commander.data.discovery

import com.wafflehq.commander.data.api.ClServerApi
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

/** All host addresses in the /24 subnet that [localIpv4] belongs to, e.g. "192.168.1.1".."192.168.1.254". */
fun subnetHosts(localIpv4: String): List<String> {
    val base = localIpv4.substringBeforeLast('.')
    return (1..254).map { "$base.$it" }
}

@Singleton
class NetworkDiscovery @Inject constructor(
    private val api: ClServerApi,
) {
    /** Scans every address in the device's local /24 subnet for a `cl server` on [port], returns the first hit. */
    suspend fun discoverHost(port: Int): String? {
        val localIp = localIpv4Address() ?: return null
        return raceFirstMatch(subnetHosts(localIp)) { host -> api.probeStatus(host, port) }
    }

    private fun localIpv4Address(): String? =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
}
