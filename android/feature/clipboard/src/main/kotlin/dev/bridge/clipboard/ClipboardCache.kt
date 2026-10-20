package dev.bridge.clipboard

class ClipboardCache(
    private val ttlMs: Long = 300_000L, // 5 minutes
    private val maxEntries: Int = 500
) {
    private val processedEventIds = mutableMapOf<String, Long>()
    private val recentContentHashes = mutableMapOf<String, Long>()

    @Synchronized
    fun recordEvent(eventId: String, contentSha256Hex: String) {
        cleanup()
        val now = System.currentTimeMillis()
        processedEventIds[eventId] = now
        recentContentHashes[contentSha256Hex] = now

        if (processedEventIds.size > maxEntries) {
            val oldest = processedEventIds.minByOrNull { it.value }?.key
            if (oldest != null) processedEventIds.remove(oldest)
        }
        if (recentContentHashes.size > maxEntries) {
            val oldest = recentContentHashes.minByOrNull { it.value }?.key
            if (oldest != null) recentContentHashes.remove(oldest)
        }
    }

    @Synchronized
    fun isEventProcessed(eventId: String): Boolean {
        cleanup()
        return processedEventIds.containsKey(eventId)
    }

    @Synchronized
    fun isContentRecent(contentSha256Hex: String): Boolean {
        cleanup()
        return recentContentHashes.containsKey(contentSha256Hex)
    }

    @Synchronized
    private fun cleanup() {
        val now = System.currentTimeMillis()
        processedEventIds.entries.removeIf { now - it.value > ttlMs }
        recentContentHashes.entries.removeIf { now - it.value > ttlMs }
    }

    @Synchronized
    fun clear() {
        processedEventIds.clear()
        recentContentHashes.clear()
    }
}
