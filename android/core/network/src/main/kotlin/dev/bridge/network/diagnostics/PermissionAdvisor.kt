package dev.bridge.network.diagnostics

enum class PermissionState {
    GRANTED,
    DENIED,
    REQUIRES_USER_ACTION
}

data class PermissionCheckItem(
    val id: String,
    val name: String,
    val state: PermissionState,
    val description: String,
    val remediationGuidance: String
)

object PermissionAdvisor {
    fun getStandardAndroidChecks(
        hasNotificationAccess: Boolean = true,
        isBatteryOptimized: Boolean = true
    ): List<PermissionCheckItem> {
        return listOf(
            PermissionCheckItem(
                id = "notification_listener",
                name = "Notification Listener Access",
                state = if (hasNotificationAccess) PermissionState.GRANTED else PermissionState.DENIED,
                description = "Required to read incoming Android notifications and forward them to Mac.",
                remediationGuidance = "Open Android Settings > Apps > Special App Access > Notification Access and enable Bridge."
            ),
            PermissionCheckItem(
                id = "battery_optimization",
                name = "Background Battery Exemption",
                state = if (!isBatteryOptimized) PermissionState.GRANTED else PermissionState.REQUIRES_USER_ACTION,
                description = "Prevents OEM battery killers from terminating the Bridge background service.",
                remediationGuidance = "Open App Info > Battery > select 'Unrestricted' or disable battery optimization for Bridge."
            ),
            PermissionCheckItem(
                id = "connected_device_service",
                name = "Foreground Service",
                state = PermissionState.GRANTED,
                description = "Runs a persistent foreground notification while actively connected to your Mac.",
                remediationGuidance = "Allows Bridge to maintain a resilient connection when using Wi-Fi or Tailscale."
            )
        )
    }
}
