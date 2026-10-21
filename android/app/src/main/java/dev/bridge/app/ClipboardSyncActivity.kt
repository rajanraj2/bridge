package dev.bridge.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

class ClipboardSyncActivity : Activity() {
    private var hasRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        val wlp = window.attributes
        wlp.flags = wlp.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        wlp.width = 1
        wlp.height = 1
        window.attributes = wlp
    }

    override fun onResume() {
        super.onResume()
        attemptReadClipboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            attemptReadClipboard()
        }
    }

    private fun attemptReadClipboard() {
        if (hasRead) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val uri = item.uri
                if (uri != null) {
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        hasRead = true
                        var fileName = "file"
                        try {
                            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1 && cursor.moveToFirst()) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        } catch (_: Exception) {}
                        println("ClipboardSyncActivity: Captured file/image on focus: '$fileName'")
                        BridgeForegroundService.dispatchLocalFileOrImage(bytes, mime, fileName)
                    }
                } else {
                    val text = item.coerceToText(this).toString()
                    if (text.isNotBlank() && text != BridgeForegroundService.lastReceivedText && text != BridgeForegroundService.lastSentText) {
                        hasRead = true
                        println("ClipboardSyncActivity: Captured clipboard on focus: '$text'")
                        BridgeForegroundService.dispatchLocalCopy(text)
                    }
                }
            }
        } catch (e: Exception) {
            println("ClipboardSyncActivity error: ${e.message}")
        }
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, 120)
    }
}
