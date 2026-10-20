import Foundation
import BridgeDomain

public struct DiscoveredPeer: Identifiable, Equatable, Sendable {
    public let id: String
    public let displayName: String
    public let host: String
    public let port: Int
    public let protocolMajor: UInt32
    public let protocolMinor: UInt32
    public let isPaired: Bool

    public init(id: String, displayName: String, host: String, port: Int, protocolMajor: UInt32, protocolMinor: UInt32, isPaired: Bool = false) {
        self.id = id
        self.displayName = displayName
        self.host = host
        self.port = port
        self.protocolMajor = protocolMajor
        self.protocolMinor = protocolMinor
        self.isPaired = isPaired
    }
}

public protocol PeerDiscoveryDelegate: AnyObject, Sendable {
    func didDiscoverPeer(_ peer: DiscoveredPeer)
    func didLosePeer(id: String)
}

public final class PeerDiscoveryService: @unchecked Sendable {
    public static let serviceType = "_bridge._tcp"
    public static let defaultPort: Int = 54884

    private let lock = NSLock()
    private(set) public var discoveredPeers: [String: DiscoveredPeer] = [:]
    public weak var delegate: PeerDiscoveryDelegate?

    public init() {}

    public func addDiscoveredPeer(_ peer: DiscoveredPeer) {
        lock.lock()
        discoveredPeers[peer.id] = peer
        lock.unlock()
        delegate?.didDiscoverPeer(peer)
    }

    public func removeDiscoveredPeer(id: String) {
        lock.lock()
        let removed = discoveredPeers.removeValue(forKey: id) != nil
        lock.unlock()
        if removed {
            delegate?.didLosePeer(id: id)
        }
    }

    public func clear() {
        lock.lock()
        discoveredPeers.removeAll()
        lock.unlock()
    }
}
