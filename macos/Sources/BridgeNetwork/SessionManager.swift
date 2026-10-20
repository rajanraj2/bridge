import Foundation
import BridgeDomain
import BridgeProtocol
import BridgeSecurity
import SwiftProtobuf

public enum SessionError: Error, Equatable {
    case unsupportedVersion
    case unauthorizedPeer(String)
    case handshakeFailed(String)
    case sessionTimeout
    case framingError(String)
    case invalidMessageSequence
}

public protocol SessionDelegate: AnyObject, Sendable {
    func sessionDidEstablish(_ session: PeerSession)
    func sessionDidReceiveEnvelope(_ envelope: Bridge_Envelope)
    func sessionDidFail(error: SessionError)
    func sessionDidClose()
}

public final class SessionManager: @unchecked Sendable {
    public static let currentProtocolMajor: UInt32 = 1
    public static let currentProtocolMinor: UInt32 = 0
    public static let defaultHeartbeatInterval: TimeInterval = 15.0
    public static let defaultHeartbeatTimeout: TimeInterval = 45.0

    private let lock = NSRecursiveLock()
    private let peerStore: TrustedPeerStorage
    private let stateMachine: ConnectionStateMachine
    public weak var delegate: SessionDelegate?

    private(set) public var currentSession: PeerSession?
    private(set) public var sequenceNumber: UInt64 = 0
    private(set) public var lastActivity: Date = Date()
    private(set) public var isEstablished: Bool = false

    public let localDeviceId: String
    public let localDisplayName: String
    public let localPlatform: Platform

    public init(
        localDeviceId: String,
        localDisplayName: String,
        localPlatform: Platform = .macOS,
        peerStore: TrustedPeerStorage,
        stateMachine: ConnectionStateMachine
    ) {
        self.localDeviceId = localDeviceId
        self.localDisplayName = localDisplayName
        self.localPlatform = localPlatform
        self.peerStore = peerStore
        self.stateMachine = stateMachine
    }

    /// Creates the initial Hello envelope for session negotiation.
    public func createHelloEnvelope(sessionId: String) -> Bridge_Envelope {
        lock.lock()
        defer { lock.unlock() }

        sequenceNumber += 1
        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = localDeviceId
        env.sequenceNumber = sequenceNumber
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = Self.currentProtocolMajor
        env.protocolMinor = Self.currentProtocolMinor

        var hello = Bridge_Hello()
        hello.deviceID = localDeviceId
        hello.displayName = localDisplayName
        hello.platform = localPlatform.rawValue

        var version = Bridge_Version()
        version.major = Self.currentProtocolMajor
        version.minor = Self.currentProtocolMinor
        hello.supportedVersions = [version]
        hello.capabilities = ["clipboard", "notifications", "transfers"]

        env.hello = hello
        return env
    }

    /// Creates a HelloAck response envelope.
    public func createHelloAckEnvelope(sessionId: String, acceptedCapabilities: [String]) -> Bridge_Envelope {
        lock.lock()
        defer { lock.unlock() }

        sequenceNumber += 1
        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = localDeviceId
        env.sequenceNumber = sequenceNumber
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = Self.currentProtocolMajor
        env.protocolMinor = Self.currentProtocolMinor

        var ack = Bridge_HelloAck()
        var version = Bridge_Version()
        version.major = Self.currentProtocolMajor
        version.minor = Self.currentProtocolMinor
        ack.selectedVersion = version
        ack.sessionID = sessionId
        ack.acceptedCapabilities = acceptedCapabilities

        env.helloAck = ack
        return env
    }

    /// Handles incoming Hello message during handshake.
    public func processIncomingHello(
        envelope: Bridge_Envelope,
        peerPublicKey: Data
    ) throws -> Bridge_Envelope {
        lock.lock()
        defer { lock.unlock() }

        guard case .hello(let hello) = envelope.payload else {
            throw SessionError.handshakeFailed("Expected Hello envelope")
        }

        // Verify version compatibility
        let isCompatible = hello.supportedVersions.contains { $0.major == Self.currentProtocolMajor }
        guard isCompatible else {
            stateMachine.handle(event: .pairingFailed("Unsupported protocol version"))
            throw SessionError.unsupportedVersion
        }

        // Verify peer identity against trusted store
        let isTrusted = peerStore.isTrusted(deviceId: hello.deviceID, publicKey: peerPublicKey)
        guard isTrusted else {
            stateMachine.handle(event: .disconnect)
            throw SessionError.unauthorizedPeer(hello.deviceID)
        }

        let peerDevice = DeviceIdentity(
            deviceId: hello.deviceID,
            displayName: hello.displayName,
            platform: Platform(rawValue: hello.platform) ?? .android,
            publicKeyFingerprint: IdentityManager.fingerprint(data: peerPublicKey)
        )

        let session = PeerSession(
            sessionId: envelope.sessionID,
            peerDevice: peerDevice,
            protocolMajor: Self.currentProtocolMajor,
            protocolMinor: Self.currentProtocolMinor
        )

        currentSession = session
        isEstablished = true
        lastActivity = Date()
        stateMachine.handle(event: .connectionEstablished(session))
        delegate?.sessionDidEstablish(session)

        return createHelloAckEnvelope(sessionId: envelope.sessionID, acceptedCapabilities: hello.capabilities)
    }

    /// Handles incoming HelloAck response.
    public func processIncomingHelloAck(
        envelope: Bridge_Envelope,
        peerDevice: DeviceIdentity,
        peerPublicKey: Data
    ) throws {
        lock.lock()
        defer { lock.unlock() }

        guard case .helloAck(let ack) = envelope.payload else {
            throw SessionError.handshakeFailed("Expected HelloAck envelope")
        }

        guard ack.selectedVersion.major == Self.currentProtocolMajor else {
            throw SessionError.unsupportedVersion
        }

        let isTrusted = peerStore.isTrusted(deviceId: peerDevice.deviceId, publicKey: peerPublicKey)
        guard isTrusted else {
            stateMachine.handle(event: .disconnect)
            throw SessionError.unauthorizedPeer(peerDevice.deviceId)
        }

        let session = PeerSession(
            sessionId: envelope.sessionID,
            peerDevice: peerDevice,
            protocolMajor: ack.selectedVersion.major,
            protocolMinor: ack.selectedVersion.minor
        )

        currentSession = session
        isEstablished = true
        lastActivity = Date()
        stateMachine.handle(event: .connectionEstablished(session))
        delegate?.sessionDidEstablish(session)
    }

    /// Generates a heartbeat envelope.
    public func createHeartbeatEnvelope() -> Bridge_Envelope {
        lock.lock()
        defer { lock.unlock() }

        sequenceNumber += 1
        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = currentSession?.sessionId ?? ""
        env.senderDeviceID = localDeviceId
        env.sequenceNumber = sequenceNumber
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = Self.currentProtocolMajor
        env.protocolMinor = Self.currentProtocolMinor

        var heartbeat = Bridge_Heartbeat()
        heartbeat.sentAtUnixMs = env.createdAtUnixMs
        env.heartbeat = heartbeat

        lastActivity = Date()
        return env
    }

    /// Processes an incoming message envelope.
    public func processIncomingEnvelope(_ envelope: Bridge_Envelope) {
        lock.lock()
        defer { lock.unlock() }

        lastActivity = Date()
        if case .heartbeat = envelope.payload {
            // Heartbeat processed, activity updated
            return
        }

        delegate?.sessionDidReceiveEnvelope(envelope)
    }

    /// Checks whether the session has timed out due to missed heartbeats.
    public func checkStaleSession(timeout: TimeInterval = defaultHeartbeatTimeout) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        guard isEstablished else { return false }
        let elapsed = Date().timeIntervalSince(lastActivity)
        if elapsed > timeout {
            isEstablished = false
            currentSession = nil
            stateMachine.handle(event: .connectionLost(temporary: true))
            delegate?.sessionDidFail(error: .sessionTimeout)
            return true
        }
        return false
    }

    public func closeSession() {
        lock.lock()
        defer { lock.unlock() }

        isEstablished = false
        currentSession = nil
        stateMachine.handle(event: .disconnect)
        delegate?.sessionDidClose()
    }
}
