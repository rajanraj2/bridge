import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeSecurity
@testable import BridgeNetwork

final class HardeningAndFuzzTests: XCTestCase {

    func testWireFramingRandomByteStreamFuzzing() {
        // Run 100 iterations of random garbage and mutated payloads
        for i in 0..<100 {
            let length = Int.random(in: 0...1024)
            var randomBytes = Data(count: length)
            for j in 0..<length {
                randomBytes[j] = UInt8.random(in: 0...255)
            }

            do {
                // Must either succeed, return nil (incomplete), or throw FramingError.
                // It must NEVER crash, hang, or enter undefined behavior.
                _ = try WireFrame.decode(from: randomBytes)
            } catch let error as FramingError {
                // Legitimate rejection of oversized/invalid payload
                XCTAssertNotNil(error)
            } catch {
                XCTFail("Unexpected non-FramingError exception on fuzz iteration \(i): \(error)")
            }
        }
    }

    func testWireFramingExtremeLengths() {
        // Negative length (large unsigned integer)
        var negativeLengthHeader = Data([0xFF, 0xFF, 0xFF, 0xFF])
        negativeLengthHeader.append(contentsOf: [0x01, 0x02, 0x03])
        XCTAssertThrowsError(try WireFrame.decode(from: negativeLengthHeader)) { error in
            guard case FramingError.messageTooLarge = error else {
                XCTFail("Expected messageTooLarge error on 0xFFFFFFFF length")
                return
            }
        }

        // Zero length
        var zeroLengthHeader = Data([0x00, 0x00, 0x00, 0x00])
        let zeroResult = try? WireFrame.decode(from: zeroLengthHeader)
        XCTAssertNotNil(zeroResult)
        XCTAssertEqual(zeroResult?.payload.count, 0)
        XCTAssertEqual(zeroResult?.consumed, 4)
    }

    func testNetworkFaultAndBackoffJitterBounds() {
        let sm = ConnectionStateMachine(maxReconnectAttempts: 5, baseReconnectDelaySeconds: 1.0)
        sm.handle(event: .startDiscovery)

        let peer = DeviceIdentity(deviceId: "android-001", displayName: "Pixel", platform: .android, publicKeyFingerprint: "AA:BB")
        sm.handle(event: .connectToPeer(peer))
        XCTAssertEqual(sm.state, .connecting)

        // Multiple transient disconnects
        for attempt in 1...5 {
            sm.handle(event: .connectionLost(temporary: true))
            XCTAssertEqual(sm.state, .reconnecting)
            XCTAssertEqual(sm.reconnectAttempts, attempt)

            let delay = sm.backoffDelay(for: attempt)
            let base = 1.0 * pow(2.0, Double(min(attempt, 6)))
            let minExpected = base * 0.79
            let maxExpected = base * 1.21
            XCTAssertTrue(delay >= minExpected && delay <= maxExpected, "Backoff delay \(delay) must stay within jitter bounds for attempt \(attempt)")
        }

        // Exhausted budget -> drops to discovering
        sm.handle(event: .connectionLost(temporary: true))
        XCTAssertEqual(sm.state, .discovering, "State must transition to discovering when retry budget is exhausted")
    }

    func testUnauthorizedPeerRejectionCannotEstablishSession() {
        let store = InMemoryTrustedPeerStore()
        let sm = ConnectionStateMachine()
        let manager = SessionManager(
            localDeviceId: "mac-001",
            localDisplayName: "Mac",
            peerStore: store,
            stateMachine: sm
        )

        // Generate untrusted peer Hello
        var env = Bridge_Envelope()
        env.messageID = "rogue-msg"
        env.sessionID = "rogue-sess"
        var hello = Bridge_Hello()
        hello.deviceID = "unauthorized-peer"
        hello.displayName = "Attacker Device"
        var ver = Bridge_Version()
        ver.major = 1
        ver.minor = 0
        hello.supportedVersions = [ver]
        env.hello = hello

        let attackerKey = "ForgedAttackerKey".data(using: .utf8)!

        XCTAssertThrowsError(try manager.processIncomingHello(envelope: env, peerPublicKey: attackerKey)) { error in
            guard case SessionError.unauthorizedPeer(let id) = error else {
                XCTFail("Expected unauthorizedPeer error")
                return
            }
            XCTAssertEqual(id, "unauthorized-peer")
        }

        XCTAssertFalse(manager.isEstablished)
        XCTAssertEqual(sm.state, .disconnected)
        XCTAssertNil(manager.currentSession)
    }
}
