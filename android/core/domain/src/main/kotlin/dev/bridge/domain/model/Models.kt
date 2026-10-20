package dev.bridge.domain.model

enum class Platform(val value: String) {
    ANDROID("android"),
    MACOS("macos")
}

data class DeviceIdentity(
    val deviceId: String,
    val displayName: String,
    val platform: Platform,
    val publicKeyFingerprint: String
)

enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    PAIRING,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class Capability(val id: String) {
    NOTIFICATIONS("notifications"),
    CLIPBOARD("clipboard"),
    FILE_TRANSFER("file_transfer")
}

enum class CapabilityState {
    ENABLED,
    DISABLED,
    DEGRADED
}

data class PeerSession(
    val sessionId: String,
    val peerDevice: DeviceIdentity,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val establishedAtUnixMs: Long = System.currentTimeMillis()
)
