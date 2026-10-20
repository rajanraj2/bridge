package dev.bridge.clipboard

data class ClipboardPolicy(
    var enabled: Boolean = true,
    var maxTextLength: Int = 500_000,
    var preventEchoLoops: Boolean = true
) {
    /**
     * Diagnostic explanation of Android platform restrictions for honest UI reporting.
     */
    val diagnosticStatus: String
        get() = if (!enabled) {
            "Clipboard sync is currently disabled in Bridge settings."
        } else {
            "Active. On Android 10+, clipboard reading is restricted in the background; foreground app or Quick Settings action is used."
        }
}
