package com.androiddrop

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible activity that reads the clipboard and sends it to the Mac.
 *
 * Why an Activity at all? Android 10+ only lets an app read the clipboard while one
 * of its windows has input focus. Notifications and Quick Settings tiles have none,
 * so they launch this tiny activity instead.
 *
 * It must be on screen for the read (focus rule), but we keep that to a single frame:
 * fully transparent theme + no animation, we grab the clipboard the instant we get
 * focus, then finish() immediately. The actual upload runs in an app-scoped coroutine
 * (see [ClipUpload]) so it survives the activity closing, and the result is shown as a
 * Toast at the bottom of the screen — no app window ever appears.
 */
class ClipSendActivity : Activity() {

    // onWindowFocusChanged can fire more than once; only act the first time we get focus.
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Intentionally no clipboard read here — at onCreate() the window has no focus yet.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        capture()

        // Leave at once so the user never sees a screen. The upload continues in the
        // background and reports via Toast.
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun capture() {
        val prefs = Prefs(this)
        if (!prefs.isConfigured) {
            toast("Open app to set Mac IP")
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)

        val imageUri = item?.uri?.let { uri ->
            try {
                if (contentResolver.getType(uri)?.startsWith("image/") == true) uri else null
            } catch (e: Exception) { null }
        }

        if (imageUri != null) {
            // Read the bytes NOW, while we still hold clipboard/URI access — then we can
            // safely finish the activity and upload from a background coroutine.
            val mime = try { contentResolver.getType(imageUri) ?: "image/png" }
                       catch (e: Exception) { "image/png" }
            val name = filenameOf(imageUri) ?: "clipboard.png"
            val bytes = try { contentResolver.openInputStream(imageUri)?.readBytes() }
                        catch (e: Exception) { null }
            if (bytes == null) { toast("Clipboard is empty"); return }
            ClipUpload.image(applicationContext, bytes, name, mime)
        } else {
            val text = item?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) { toast("Clipboard is empty"); return }
            ClipUpload.text(applicationContext, text)
        }
    }

    private fun filenameOf(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        }
    } catch (e: Exception) { null }

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}

/**
 * Fire-and-forget clipboard upload on an app-scoped coroutine, so it outlives the
 * instantly-finishing [ClipSendActivity]. Reuses [Sender] for mDNS rediscovery on
 * failure and shows the outcome as a Toast.
 */
private object ClipUpload {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun text(context: Context, text: String) = scope.launch {
        val prefs = Prefs(context)
        report(context, try {
            val r = Sender.send(context, prefs) { Uploader.uploadText(prefs, text) }
            if (r.isSuccessful) "✓ Clipboard sent to Mac" else "Error ${r.code}"
        } catch (e: Exception) { "× ${e.message?.take(40)}" })
    }

    fun image(context: Context, bytes: ByteArray, filename: String, mime: String) = scope.launch {
        val prefs = Prefs(context)
        report(context, try {
            val r = Sender.send(context, prefs) { Uploader.uploadBytes(prefs, bytes, filename, mime) }
            if (r.isSuccessful) "✓ Image sent to Mac" else "Error ${r.code}"
        } catch (e: Exception) { "× ${e.message?.take(40)}" })
    }

    private suspend fun report(context: Context, msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
