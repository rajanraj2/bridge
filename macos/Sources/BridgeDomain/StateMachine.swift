import Foundation

public enum ConnectionEvent: Sendable {
    case startDiscovery
    case peerDiscovered(DeviceIdentity)
    case initiatePairing(DeviceIdentity)
    case pairingSucceeded
    case pairingFailed(String)
    case connectToPeer(DeviceIdentity)
    case connectionEstablished(PeerSession)
    case connectionLost(temporary: Bool)
    case retryBudgetExhausted
    case disconnect
}

public final class ConnectionStateMachine: @unchecked Sendable {
    private let lock = NSLock()
    private(set) public var state: ConnectionState = .disconnected
    private(set) public var currentPeer: DeviceIdentity?
    private(set) public var currentSession: PeerSession?
    private(set) public var reconnectAttempts: Int = 0

    public let maxReconnectAttempts: Int
    public let baseReconnectDelaySeconds: Double

    public init(maxReconnectAttempts: Int = 5, baseReconnectDelaySeconds: Double = 1.0) {
        self.maxReconnectAttempts = maxReconnectAttempts
        self.baseReconnectDelaySeconds = baseReconnectDelaySeconds
    }

    @discardableResult
    public func handle(event: ConnectionEvent) -> ConnectionState {
        lock.lock()
        defer { lock.unlock() }

        switch (state, event) {
        case (.disconnected, .startDiscovery):
            state = .discovering

        case (.discovering, .initiatePairing(let peer)):
            currentPeer = peer
            state = .pairing

        case (.discovering, .connectToPeer(let peer)):
            currentPeer = peer
            state = .connecting

        case (.pairing, .pairingSucceeded):
            state = .connecting

        case (.pairing, .pairingFailed):
            currentPeer = nil
            state = .disconnected

        case (.connecting, .connectionEstablished(let session)):
            currentSession = session
            currentPeer = session.peerDevice
            reconnectAttempts = 0
            state = .connected

        case (.connecting, .connectionLost(let temporary)):
            if temporary && reconnectAttempts < maxReconnectAttempts {
                reconnectAttempts += 1
                state = .reconnecting
            } else {
                state = .disconnected
            }

        case (.connected, .connectionLost(let temporary)):
            currentSession = nil
            if temporary && reconnectAttempts < maxReconnectAttempts {
                reconnectAttempts += 1
                state = .reconnecting
            } else {
                state = .disconnected
            }

        case (.reconnecting, .connectionEstablished(let session)):
            currentSession = session
            currentPeer = session.peerDevice
            reconnectAttempts = 0
            state = .connected

        case (.reconnecting, .connectionLost):
            if reconnectAttempts < maxReconnectAttempts {
                reconnectAttempts += 1
                state = .reconnecting
            } else {
                state = .discovering
            }

        case (.reconnecting, .retryBudgetExhausted):
            state = .discovering

        case (_, .disconnect):
            state = .disconnected
            currentPeer = nil
            currentSession = nil
            reconnectAttempts = 0

        default:
            break
        }

        return state
    }

    public func backoffDelay(for attempt: Int) -> Double {
        let exponential = baseReconnectDelaySeconds * pow(2.0, Double(min(attempt, 6)))
        let jitter = Double.random(in: 0.8...1.2)
        return exponential * jitter
    }
}
