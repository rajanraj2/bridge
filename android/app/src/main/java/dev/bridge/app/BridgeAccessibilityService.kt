package dev.bridge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        var isRunning: Boolean = false
        var lastSyncedText: String? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        println("BridgeAccessibilityService: connected and monitoring system-wide copy events!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Detect user clicking "Copy" or "Cut" in context menus / popups
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val text = event.text.joinToString(" ")
            val desc = event.contentDescription?.toString() ?: ""
            if (isCopyAction(text) || isCopyAction(desc)) {
                println("BridgeAccessibilityService: detected user click on Copy action! text='$text', desc='$desc'")
                BridgeForegroundService.triggerTrampolineFromService(this)
                return
            }
        }

        // 2. Detect SystemUI showing ClipboardOverlay
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            val cls = event.className?.toString() ?: ""
            if (pkg == "com.android.systemui" && cls.contains("Clipboard", ignoreCase = true)) {
                println("BridgeAccessibilityService: detected SystemUI ClipboardOverlay ($cls)!")
                BridgeForegroundService.triggerTrampolineFromService(this)
                return
            }
        }
    }

    private fun isCopyAction(str: String): Boolean {
        if (str.isBlank()) return false
        val s = str.lowercase()
        return s.contains("copy") || s.contains("cut") || s.contains("复制") || s.contains("剪切") || s.contains("copier")
    }

    override fun onInterrupt() {
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
