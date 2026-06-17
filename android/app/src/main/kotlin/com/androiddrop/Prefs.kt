package com.androiddrop

import android.content.Context

/**
 * SharedPreferences wrapper — persists the Mac IP, port, and token between app launches.
 *
 * SharedPreferences is Android's simple key-value store, like a small JSON file on disk.
 * In Kotlin, `var ip: String get()/set()` is a property with custom getter and setter —
 * similar to Python's @property decorator.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("androiddrop", Context.MODE_PRIVATE)

    var ip: String
        get() = sp.getString("ip", "") ?: ""
        set(v) { sp.edit().putString("ip", v).apply() }

    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) { sp.edit().putInt("port", v).apply() }

    var token: String
        get() = sp.getString("token", "changeme") ?: "changeme"
        set(v) { sp.edit().putString("token", v).apply() }

    // Highest clipboard "seq" we've already notified about (Mac → Android), so we don't
    // re-show the same item when the WebSocket reconnects and replays the current clip.
    var lastInboxSeq: Int
        get() = sp.getInt("last_inbox_seq", -1)
        set(v) { sp.edit().putInt("last_inbox_seq", v).apply() }

    // Same idea as lastInboxSeq, but for the separate "file send" channel (AirDrop-like
    // arbitrary files from the Mac). Kept apart so a clipboard copy never hides a file.
    var lastFileSeq: Int
        get() = sp.getInt("last_file_seq", -1)
        set(v) { sp.edit().putInt("last_file_seq", v).apply() }

    // Pinned TLS public-key of the Mac ("sha256/…"), learned on first connect.
    // Empty = not paired yet (trust the next server we see). See [Net].
    var certPin: String
        get() = sp.getString("cert_pin", "") ?: ""
        set(v) { sp.edit().putString("cert_pin", v).apply() }

    val baseUrl: String get() = "https://$ip:$port"
    val isConfigured: Boolean get() = ip.isNotBlank()
}
