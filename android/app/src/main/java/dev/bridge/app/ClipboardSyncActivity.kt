package dev.bridge.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

class ClipboardSyncActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val wlp = window.attributes
        wlp.flags = wlp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wlp.width = 1
        wlp.height = 1
        window.attributes = wlp
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank() && text != BridgeForegroundService.lastReceivedText) {
                        println("ClipboardSyncActivity: Captured clipboard on window focus: '$text'")
                        BridgeForegroundService.dispatchLocalCopy(text)
                    }
                }
            } catch (e: Exception) {
                println("ClipboardSyncActivity error: ${e.message}")
            } finally {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }
}
