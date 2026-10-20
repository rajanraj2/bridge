import Foundation

public enum Platform: String, Codable, Sendable {
    case android = "android"
    case macOS = "macos"
}

public struct DeviceIdentity: Codable, Equatable, Sendable {
    public let deviceId: String
    public let displayName: String
    public let platform: Platform
    public let publicKeyFingerprint: String

    public init(deviceId: String, displayName: String, platform: Platform, publicKeyFingerprint: String) {
        self.deviceId = deviceId
        self.displayName = displayName
        self.platform = platform
        self.publicKeyFingerprint = publicKeyFingerprint
    }
}

public enum ConnectionState: String, Codable, Sendable {
    case disconnected
    case discovering
    case pairing
    case connecting
    case connected
    case reconnecting
}

public enum Capability: String, Codable, CaseIterable, Sendable {
    case notifications
    case clipboard
    case fileTransfer = "file_transfer"
}

public enum CapabilityState: String, Codable, Sendable {
    case enabled
    case disabled
    case degraded
}

public struct PeerSession: Codable, Equatable, Sendable {
    public let sessionId: String
    public let peerDevice: DeviceIdentity
    public let protocolMajor: UInt32
    public let protocolMinor: UInt32
    public let establishedAt: Date

    public init(sessionId: String, peerDevice: DeviceIdentity, protocolMajor: UInt32, protocolMinor: UInt32, establishedAt: Date = Date()) {
        self.sessionId = sessionId
        self.peerDevice = peerDevice
        self.protocolMajor = protocolMajor
        self.protocolMinor = protocolMinor
        self.establishedAt = establishedAt
    }
}
