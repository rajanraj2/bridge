package dev.bridge.notifications

class NotificationDeduplicator(
    private val ttlMs: Long = 600_000L, // 10 minutes
    private val maxEntries: Int = 1000
) {
    private val seenEvents = mutableMapOf<String, Long>()

    @Synchronized
    fun recordIfNotSeen(eventId: String): Boolean {
        cleanupExpired()

        if (seenEvents.containsKey(eventId)) {
            return false
        }

        if (seenEvents.size >= maxEntries) {
            val oldestKey = seenEvents.minByOrNull { it.value }?.key
            if (oldestKey != null) {
                seenEvents.remove(oldestKey)
            }
        }

        seenEvents[eventId] = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun isDuplicate(eventId: String): Boolean {
        cleanupExpired()
        return seenEvents.containsKey(eventId)
    }

    @Synchronized
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        seenEvents.entries.removeIf { now - it.value > ttlMs }
    }

    @Synchronized
    fun clear() {
        seenEvents.clear()
    }
}
