package com.androiddrop

import android.util.Base64
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Builds OkHttp clients that talk TLS to the Mac's self-signed HTTPS/WSS server.
 *
 * The Mac uses a self-signed certificate, so the system trust store won't accept it.
 * Instead we PIN the server's public key (SubjectPublicKeyInfo SHA-256) trust-on-first-use:
 * the first connection remembers the key; every later one must present the same key, else
 * we refuse — that's how a man-in-the-middle is caught. Pinning the key (not the hostname)
 * lets the Mac's IP change freely, so we also skip hostname verification: security here comes
 * entirely from the pin.
 */
object Net {
    @Volatile private var shared: OkHttpClient? = null

    /** The shared, pinned client. Derive per-use variants via base().newBuilder(). */
    fun base(): OkHttpClient =
        shared ?: synchronized(this) { shared ?: build().also { shared = it } }

    private fun build(): OkHttpClient {
        val tm: X509TrustManager = PinningTrustManager
        val ssl = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }   // self-signed/IP — the SPKI pin is the real check
            .build()
    }
}

/** Trust-on-first-use public-key pinning against the value saved in [Prefs.certPin]. */
private object PinningTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate from server")
        val pin = pinOf(leaf)
        val prefs = Prefs(App.ctx)
        when (val stored = prefs.certPin) {
            "" -> prefs.certPin = pin                    // trust on first use
            pin -> Unit                                  // matches — all good
            else -> throw CertificateException(
                "Server key changed (got $pin, expected $stored) — possible MITM. " +
                "Re-pair in AndroidDrop settings if you switched Macs."
            )
        }
    }

    private fun pinOf(cert: X509Certificate): String {
        // cert.publicKey.encoded is the DER SubjectPublicKeyInfo — same bytes the Mac hashes.
        val sha = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return "sha256/" + Base64.encodeToString(sha, Base64.NO_WRAP)
    }
}
