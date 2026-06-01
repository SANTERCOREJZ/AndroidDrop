package com.androiddrop

import android.content.Context
import okhttp3.Response
import java.io.IOException

/**
 * Wraps a network call with automatic Mac rediscovery — the "hybrid" behaviour.
 *
 * We keep a saved IP for speed, but if the Mac got a new address the first attempt
 * throws an [IOException] (connection refused / timeout / unknown host). When that
 * happens we browse the LAN via [Discovery], save the freshly found IP + port into
 * [Prefs], and retry once. So the app self-heals after the Mac's IP changes.
 *
 * [block] re-reads prefs.baseUrl every time it runs, so the retry automatically
 * targets the rediscovered address. Note we only rediscover on IOException — an
 * HTTP error like 401/500 comes back as a normal Response, not an exception, and
 * means the Mac *was* reachable, so retrying a different IP would be wrong.
 */
object Sender {
    suspend fun send(context: Context, prefs: Prefs, block: () -> Response): Response {
        return try {
            block()
        } catch (e: IOException) {
            val found = Discovery.findMac(context) ?: throw e
            prefs.ip = found.ip
            prefs.port = found.port
            block()  // retry once against the rediscovered address
        }
    }
}
