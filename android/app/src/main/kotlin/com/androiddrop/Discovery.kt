package com.androiddrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the Mac on the local network via mDNS — the same zero-config tech that
 * lets AirPlay receivers and network printers show up by themselves.
 *
 * The Mac advertises a service called "_androiddrop._tcp" (see mac/discovery.py).
 * Android's built-in NsdManager (Network Service Discovery) browses for it and
 * resolves the *current* IP + port. That's how we survive the Mac's IP changing:
 * no manual reconfiguration on the phone.
 *
 * NsdManager is callback-based and a little verbose, so we wrap it in a single
 * suspend function. A coroutine can then just write:
 *     val mac = Discovery.findMac(context)   // null if nothing answered in time
 */
object Discovery {
    // Note: NsdManager wants the bare type WITHOUT the trailing ".local." that the
    // Python side uses — Android appends that part itself.
    const val SERVICE_TYPE = "_androiddrop._tcp."

    data class Found(val ip: String, val port: Int)

    /** Browse the LAN for up to [timeoutMs] ms. Returns the first Mac found, or null. */
    suspend fun findMac(context: Context, timeoutMs: Long = 8_000): Found? =
        withTimeoutOrNull(timeoutMs) {
            val nsd = context.applicationContext
                .getSystemService(Context.NSD_SERVICE) as NsdManager

            suspendCancellableCoroutine { cont ->
                var done = false
                lateinit var discoveryListener: NsdManager.DiscoveryListener

                // Stop browsing + hand the result back. Safe to call from any callback;
                // guarded so it only fires once.
                fun finish(result: Found?) {
                    if (done) return
                    done = true
                    try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(result)
                }

                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, code: Int) { /* keep browsing */ }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val ip = si.host?.hostAddress ?: return
                        finish(Found(ip, si.port))
                    }
                }

                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(type: String, code: Int) = finish(null)
                    override fun onStopDiscoveryFailed(type: String, code: Int) {}
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onServiceFound(si: NsdServiceInfo) {
                        // Found a service of the right type; resolve it to learn its IP + port.
                        @Suppress("DEPRECATION")  // resolveService still works on API 34+; the
                        nsd.resolveService(si, resolveListener)  // replacement needs API 34.
                    }
                    override fun onServiceLost(si: NsdServiceInfo) {}
                }

                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

                // If the coroutine times out (withTimeoutOrNull) it gets cancelled —
                // make sure we stop the browse so it doesn't leak.
                cont.invokeOnCancellation {
                    try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
                }
            }
        }
}
