import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeSecurity
@testable import BridgeNetwork

final class SessionManagerTests: XCTestCase {

    func testSuccessfulHelloNegotiationAndHeartbeats() throws {
        let store = InMemoryTrustedPeerStore()
        let sm = ConnectionStateMachine()
        let manager = SessionManager(
            localDeviceId: "mac-001",
            localDisplayName: "MacBook",
            localPlatform: .macOS,
            peerStore: store,
            stateMachine: sm
        )

        let peerKey = "AndroidKeyBytes12345".data(using: .utf8)!
        store.savePeer(TrustedPeer(deviceId: "android-001", displayName: "Pixel", publicKey: peerKey))

        // Create client Hello
        let clientManager = SessionManager(
            localDeviceId: "android-001",
            localDisplayName: "Pixel",
            localPlatform: .android,
            peerStore: store,
            stateMachine: ConnectionStateMachine()
        )
        let helloEnv = clientManager.createHelloEnvelope(sessionId: "sess-999")

        // Server processes Hello
        let ackEnv = try manager.processIncomingHello(envelope: helloEnv, peerPublicKey: peerKey)
        XCTAssertTrue(manager.isEstablished)
        XCTAssertEqual(manager.currentSession?.sessionId, "sess-999")
        XCTAssertEqual(sm.state, .connected)

        // Verify HelloAck
        guard case .helloAck(let ack) = ackEnv.payload else {
            XCTFail("Expected helloAck")
            return
        }
        XCTAssertEqual(ack.selectedVersion.major, 1)

        // Heartbeats
        let hb1 = manager.createHeartbeatEnvelope()
        let hb2 = manager.createHeartbeatEnvelope()
        XCTAssertGreaterThan(hb2.sequenceNumber, hb1.sequenceNumber)
        XCTAssertEqual(hb2.sessionID, "sess-999")

        manager.processIncomingEnvelope(hb1)
        XCTAssertFalse(manager.checkStaleSession(timeout: 10.0))
    }

    func testUnauthorizedPeerRejected() {
        let store = InMemoryTrustedPeerStore()
        let sm = ConnectionStateMachine()
        let manager = SessionManager(
            localDeviceId: "mac-001",
            localDisplayName: "MacBook",
            peerStore: store,
            stateMachine: sm
        )

        // Peer is NOT in store
        let clientManager = SessionManager(
            localDeviceId: "attacker-001",
            localDisplayName: "Rogue Device",
            peerStore: store,
            stateMachine: ConnectionStateMachine()
        )
        let helloEnv = clientManager.createHelloEnvelope(sessionId: "sess-rogue")

        let rogueKey = "RogueKeyBytes".data(using: .utf8)!
        XCTAssertThrowsError(try manager.processIncomingHello(envelope: helloEnv, peerPublicKey: rogueKey)) { error in
            guard case SessionError.unauthorizedPeer = error else {
                XCTFail("Expected unauthorizedPeer error")
                return
            }
        }
        XCTAssertFalse(manager.isEstablished)
        XCTAssertEqual(sm.state, .disconnected)
    }

    func testIncompatibleProtocolVersionRejected() {
        let store = InMemoryTrustedPeerStore()
        let sm = ConnectionStateMachine()
        let manager = SessionManager(
            localDeviceId: "mac-001",
            localDisplayName: "MacBook",
            peerStore: store,
            stateMachine: sm
        )

        let peerKey = "PeerKey".data(using: .utf8)!
        store.savePeer(TrustedPeer(deviceId: "android-001", displayName: "Pixel", publicKey: peerKey))

        var env = Bridge_Envelope()
        env.messageID = "m1"
        env.sessionID = "s1"
        var hello = Bridge_Hello()
        hello.deviceID = "android-001"
        hello.displayName = "Pixel"
        var incompatibleVersion = Bridge_Version()
        incompatibleVersion.major = 99 // Future incompatible version
        incompatibleVersion.minor = 0
        hello.supportedVersions = [incompatibleVersion]
        env.hello = hello

        XCTAssertThrowsError(try manager.processIncomingHello(envelope: env, peerPublicKey: peerKey)) { error in
            XCTAssertEqual(error as? SessionError, .unsupportedVersion)
        }
        XCTAssertFalse(manager.isEstablished)
    }
}
