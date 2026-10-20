import Foundation
import BridgeDomain
import BridgeSecurity

public enum ConnectionRouteType: String, Codable, Sendable {
    case localLAN = "local_lan"
    case remoteTailscale = "remote_tailscale"
}

public struct RemoteEndpointConfig: Codable, Equatable, Sendable {
    public let deviceId: String
    public var tailscaleHost: String // IP (e.g. 100.x.y.z) or MagicDNS hostname
    public var port: Int
    public var isEnabled: Bool

    public init(deviceId: String, tailscaleHost: String, port: Int = 54884, isEnabled: Bool = true) {
        self.deviceId = deviceId
        self.tailscaleHost = tailscaleHost
        self.port = port
        self.isEnabled = isEnabled
    }

    public var isValid: Bool {
        !tailscaleHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (1...65535).contains(port)
    }
}

public protocol RemoteEndpointStorage: Sendable {
    func getEndpoint(for deviceId: String) -> RemoteEndpointConfig?
    func saveEndpoint(_ config: RemoteEndpointConfig)
    func removeEndpoint(for deviceId: String)
    func allEndpoints() -> [RemoteEndpointConfig]
}

public final class InMemoryRemoteEndpointStore: RemoteEndpointStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var endpoints: [String: RemoteEndpointConfig] = [:]

    public init() {}

    public func getEndpoint(for deviceId: String) -> RemoteEndpointConfig? {
        lock.lock()
        defer { lock.unlock() }
        return endpoints[deviceId]
    }

    public func saveEndpoint(_ config: RemoteEndpointConfig) {
        lock.lock()
        defer { lock.unlock() }
        endpoints[config.deviceId] = config
    }

    public func removeEndpoint(for deviceId: String) {
        lock.lock()
        defer { lock.unlock() }
        endpoints.removeValue(forKey: deviceId)
    }

    public func allEndpoints() -> [RemoteEndpointConfig] {
        lock.lock()
        defer { lock.unlock() }
        return Array(endpoints.values)
    }
}

public struct ResolvedRoute: Equatable, Sendable {
    public let deviceId: String
    public let host: String
    public let port: Int
    public let routeType: ConnectionRouteType
}

public final class EndpointRouter: @unchecked Sendable {
    private let lock = NSLock()
    private let discoveryService: PeerDiscoveryService
    private let endpointStore: RemoteEndpointStorage

    public init(
        discoveryService: PeerDiscoveryService,
        endpointStore: RemoteEndpointStorage
    ) {
        self.discoveryService = discoveryService
        self.endpointStore = endpointStore
    }

    /// Resolves the connection route for a target device:
    /// 1. Checks local mDNS discovery first.
    /// 2. If not found locally, falls back to configured Tailscale endpoint.
    /// Returns nil if neither local discovery nor remote endpoint is available.
    public func resolveRoute(for deviceId: String) -> ResolvedRoute? {
        lock.lock()
        defer { lock.unlock() }

        // 1. Local-first check
        if let localPeer = discoveryService.discoveredPeers[deviceId] {
            return ResolvedRoute(
                deviceId: deviceId,
                host: localPeer.host,
                port: localPeer.port,
                routeType: .localLAN
            )
        }

        // 2. Remote Tailscale fallback
        if let remote = endpointStore.getEndpoint(for: deviceId), remote.isEnabled, remote.isValid {
            return ResolvedRoute(
                deviceId: deviceId,
                host: remote.tailscaleHost,
                port: remote.port,
                routeType: .remoteTailscale
            )
        }

        return nil
    }
}
