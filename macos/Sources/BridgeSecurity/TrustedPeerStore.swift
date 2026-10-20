import Foundation
import BridgeDomain

public struct TrustedPeer: Codable, Equatable, Sendable, Identifiable {
    public var id: String { deviceId }
    public let deviceId: String
    public let displayName: String
    public let publicKey: Data
    public let publicKeyFingerprint: String
    public let pairedAt: Date
    public var lastSeenAt: Date

    public init(deviceId: String, displayName: String, publicKey: Data, pairedAt: Date = Date(), lastSeenAt: Date = Date()) {
        self.deviceId = deviceId
        self.displayName = displayName
        self.publicKey = publicKey
        self.publicKeyFingerprint = IdentityManager.fingerprint(data: publicKey)
        self.pairedAt = pairedAt
        self.lastSeenAt = lastSeenAt
    }
}

public protocol TrustedPeerStorage: Sendable {
    func isTrusted(deviceId: String, publicKey: Data) -> Bool
    func getPeer(deviceId: String) -> TrustedPeer?
    func savePeer(_ peer: TrustedPeer)
    func revokePeer(deviceId: String) -> Bool
    func allPeers() -> [TrustedPeer]
    func clear()
}

public final class InMemoryTrustedPeerStore: TrustedPeerStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var peers: [String: TrustedPeer] = [:]

    public init() {}

    public func isTrusted(deviceId: String, publicKey: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let peer = peers[deviceId] else { return false }
        return peer.publicKey == publicKey
    }

    public func getPeer(deviceId: String) -> TrustedPeer? {
        lock.lock()
        defer { lock.unlock() }
        return peers[deviceId]
    }

    public func savePeer(_ peer: TrustedPeer) {
        lock.lock()
        defer { lock.unlock() }
        peers[peer.deviceId] = peer
    }

    @discardableResult
    public func revokePeer(deviceId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return peers.removeValue(forKey: deviceId) != nil
    }

    public func allPeers() -> [TrustedPeer] {
        lock.lock()
        defer { lock.unlock() }
        return Array(peers.values)
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        peers.removeAll()
    }
}
