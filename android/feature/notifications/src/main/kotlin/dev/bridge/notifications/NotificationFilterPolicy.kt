package dev.bridge.notifications

data class NotificationFilterPolicy(
    var enabled: Boolean = true,
    var allowedPackages: Set<String> = emptySet(),
    var blockedPackages: Set<String> = emptySet(),
    var suppressSensitive: Boolean = true
) {
    fun shouldAllow(packageId: String, isSensitive: Boolean): Boolean {
        if (!enabled) return false
        if (suppressSensitive && isSensitive) return false
        if (blockedPackages.contains(packageId)) return false
        if (allowedPackages.isNotEmpty() && !allowedPackages.contains(packageId)) return false
        return true
    }
}
