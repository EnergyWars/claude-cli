package com.wafflehq.commander.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.wafflehq.commander.data.api.ClServerApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_CONCURRENCY = 32
private const val WIFI_LOOKUP_TIMEOUT_MS = 500L

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
    @param:ApplicationContext private val context: Context,
) {
    /** Scans every address in the device's local /24 subnet for a `cl server` on [port], returns the first hit. */
    suspend fun discoverHost(port: Int): String? {
        val localIp = localIpv4Address() ?: return null
        return raceFirstMatch(subnetHosts(localIp)) { host -> api.probeStatus(host, port) }
    }

    /**
     * The device's IPv4 address on its Wi-Fi network, if any is connected. Cellular data or a VPN
     * can also be "up" at the same time (or even be the active/default network when Wi-Fi has no
     * internet access) but neither shares the LAN the `cl server` runs on, so the Wi-Fi transport
     * is looked up explicitly instead of trusting whichever interface the OS returns first.
     */
    internal suspend fun localIpv4Address(): String? =
        wifiIpv4Address() ?: firstNonLoopbackIpv4Address()

    /**
     * `ConnectivityManager.allNetworks` is deprecated with no synchronous replacement, so the
     * currently up Wi-Fi network is looked up via a [ConnectivityManager.NetworkCallback], which
     * reports the already-connected network's link properties immediately upon registration.
     */
    internal suspend fun wifiIpv4Address(): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val address = CompletableDeferred<String?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                linkProperties.firstNonLoopbackIpv4HostAddress()?.let(address::complete)
            }
        }
        connectivityManager.registerNetworkCallback(wifiNetworkRequest(), callback)
        return try {
            withTimeoutOrNull(WIFI_LOOKUP_TIMEOUT_MS) { address.await() }
        } finally {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    internal fun wifiNetworkRequest(): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

    internal fun firstNonLoopbackIpv4Address(): String? =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
}

private fun LinkProperties.firstNonLoopbackIpv4HostAddress(): String? =
    linkAddresses
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
