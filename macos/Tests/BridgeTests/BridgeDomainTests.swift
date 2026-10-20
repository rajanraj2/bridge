import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeSecurity
@testable import BridgeNetwork

final class BridgeDomainTests: XCTestCase {
    func testStateMachineNormalLifecycle() {
        let sm = ConnectionStateMachine(maxReconnectAttempts: 3)
        XCTAssertEqual(sm.state, .disconnected)

        sm.handle(event: .startDiscovery)
        XCTAssertEqual(sm.state, .discovering)

        let peer = DeviceIdentity(deviceId: "android-001", displayName: "Pixel 8", platform: .android, publicKeyFingerprint: "AA:BB:CC")
        sm.handle(event: .initiatePairing(peer))
        XCTAssertEqual(sm.state, .pairing)
        XCTAssertEqual(sm.currentPeer?.deviceId, "android-001")

        sm.handle(event: .pairingSucceeded)
        XCTAssertEqual(sm.state, .connecting)

        let session = PeerSession(sessionId: "sess-100", peerDevice: peer, protocolMajor: 1, protocolMinor: 0)
        sm.handle(event: .connectionEstablished(session))
        XCTAssertEqual(sm.state, .connected)
        XCTAssertEqual(sm.currentSession?.sessionId, "sess-100")

        sm.handle(event: .connectionLost(temporary: true))
        XCTAssertEqual(sm.state, .reconnecting)
        XCTAssertEqual(sm.reconnectAttempts, 1)

        sm.handle(event: .connectionEstablished(session))
        XCTAssertEqual(sm.state, .connected)
        XCTAssertEqual(sm.reconnectAttempts, 0)

        sm.handle(event: .disconnect)
        XCTAssertEqual(sm.state, .disconnected)
        XCTAssertNil(sm.currentSession)
    }

    func testWireFramingEncodeDecode() throws {
        let samplePayload = "Test Bridge Message Payload".data(using: .utf8)!
        let encoded = try WireFrame.encode(payload: samplePayload)

        XCTAssertEqual(encoded.count, 4 + samplePayload.count)

        let result = try WireFrame.decode(from: encoded)
        XCTAssertNotNil(result)
        XCTAssertEqual(result?.payload, samplePayload)
        XCTAssertEqual(result?.consumed, encoded.count)
    }

    func testWireFramingOversizedRejection() {
        let oversizedData = Data(repeating: 0x41, count: WireFrame.maxControlFrameSize + 10)
        XCTAssertThrowsError(try WireFrame.encode(payload: oversizedData)) { error in
            guard case FramingError.messageTooLarge = error else {
                XCTFail("Expected messageTooLarge error")
                return
            }
        }
    }

    func testShortAuthenticationStringDeterminism() {
        let identityA = "AlicePublicIdentityKeyDataBytes123".data(using: .utf8)!
        let identityB = "BobPublicIdentityKeyDataBytes456".data(using: .utf8)!
        let challenge = "RandomChallengeNonce789".data(using: .utf8)!

        let sas1 = IdentityManager.computeSAS(localIdentity: identityA, peerIdentity: identityB, challenge: challenge)
        let sas2 = IdentityManager.computeSAS(localIdentity: identityB, peerIdentity: identityA, challenge: challenge)

        XCTAssertEqual(sas1, sas2, "SAS code must be identical regardless of local/peer argument order")
        XCTAssertEqual(sas1.count, 6, "SAS code must be exactly 6 digits")
    }

    func testDiscoveryServiceTracking() {
        let service = PeerDiscoveryService()
        let peer = DiscoveredPeer(id: "dev-mac-1", displayName: "MacBook", host: "192.168.1.50", port: 54884, protocolMajor: 1, protocolMinor: 0)

        service.addDiscoveredPeer(peer)
        XCTAssertEqual(service.discoveredPeers.count, 1)
        XCTAssertEqual(service.discoveredPeers["dev-mac-1"]?.displayName, "MacBook")

        service.removeDiscoveredPeer(id: "dev-mac-1")
        XCTAssertEqual(service.discoveredPeers.count, 0)
    }
}
