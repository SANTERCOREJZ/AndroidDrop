package com.androiddrop

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Quick Settings tile — the button in the notification shade / quick settings panel.
 *
 * Lifecycle:
 *   onStartListening() — tile is visible: ping Mac, show connection status
 *   onClick()          — user tapped: send the clipboard
 *   onDestroy()        — tile removed / service killed: cancel coroutines
 *
 * On tap we DON'T read the clipboard here. Android 10+ only allows clipboard reads
 * while a window has input focus, and a tile service has none. So we launch the
 * transparent ClipSendActivity (via startActivityAndCollapse), which reads the
 * clipboard once it gains focus and sends it — the exact same path the notification
 * "Send Clipboard" button uses.
 *
 * Tile states:
 *   STATE_ACTIVE   = colored → Mac reachable / ready
 *   STATE_INACTIVE = grey    → Mac not reachable / not configured
 */
class ClipboardTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Short-timeout client just for the health-check ping — we don't want to block the shade for 10s.
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // Called every time the tile becomes visible (user pulls down the shade).
    override fun onStartListening() {
        super.onStartListening()
        val prefs = Prefs(this)

        if (!prefs.isConfigured) {
            setTile(Tile.STATE_INACTIVE, "Open app to set IP")
            return
        }

        setTile(Tile.STATE_ACTIVE, "Checking…")

        scope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    pingClient.newCall(
                        Request.Builder()
                            .url("${prefs.baseUrl}/health")
                            .header("x-token", prefs.token)
                            .get()
                            .build()
                    ).execute().isSuccessful
                } catch (e: Exception) {
                    false
                }
            }
            setTile(
                state    = if (reachable) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
                subtitle = if (reachable) "Mac connected" else "Mac unreachable"
            )
        }
    }

    override fun onClick() {
        super.onClick()

        val prefs = Prefs(this)
        if (!prefs.isConfigured) {
            setTile(Tile.STATE_INACTIVE, "Open app to set IP")
            return
        }

        // Hand off to the transparent activity, which can legally read the clipboard
        // once it has focus, then sends it (with mDNS rediscovery on failure).
        val intent = Intent(this, ClipSendActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }

        // startActivityAndCollapse: launch the activity and close the shade. Its signature
        // changed in Android 14 (API 34) from Intent to PendingIntent — support both.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun setTile(state: Int, subtitle: String) {
        qsTile?.apply {
            this.state = state
            this.label = "AndroidDrop"
            // subtitle is supported from Android 10 (API 29)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                this.subtitle = subtitle
            }
            updateTile()
        }
    }
}
