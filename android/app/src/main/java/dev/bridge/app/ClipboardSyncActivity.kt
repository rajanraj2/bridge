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
        val wlp = window.attributes
        wlp.flags = wlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank() && text != BridgeForegroundService.lastReceivedText && text != BridgeForegroundService.lastSentText) {
                    hasRead = true
                    println("ClipboardSyncActivity: Captured clipboard on focus: '$text'")
                    BridgeForegroundService.dispatchLocalCopy(text)
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
