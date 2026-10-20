import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeSecurity

final class PairingSecurityTests: XCTestCase {

    func testPairingChallengeAndSASFlow() throws {
        let store = InMemoryTrustedPeerStore()
        let coordinator = PairingCoordinator(store: store)

        let macKey = "MacPublicKeyBytesEd25519SampleData1".data(using: .utf8)!
        let androidKey = "AndroidPublicKeyBytesEd25519SampleData2".data(using: .utf8)!

        let challengePayload = coordinator.createChallenge(
            deviceId: "mac-001",
            displayName: "MacBook Pro",
            publicKey: macKey,
            port: 54884,
            ttlSeconds: 60
        )

        XCTAssertFalse(challengePayload.isExpired)
        XCTAssertEqual(challengePayload.deviceId, "mac-001")
        XCTAssertNotNil(challengePayload.publicKey)
        XCTAssertNotNil(challengePayload.challenge)

        // Android scans QR and computes SAS
        let macSAS = coordinator.computeSAS(
            localIdentity: macKey,
            peerIdentity: androidKey,
            challenge: challengePayload.challenge!
        )

        let androidSAS = coordinator.computeSAS(
            localIdentity: androidKey,
            peerIdentity: macKey,
            challenge: challengePayload.challenge!
        )

        XCTAssertEqual(macSAS, androidSAS, "SAS codes on both devices must match")

        // User approves SAS match on both screens
        let trustedPeer = try coordinator.completePairing(
            pairingId: challengePayload.pairingId,
            peerDeviceId: "android-001",
            peerDisplayName: "Pixel 8 Pro",
            peerPublicKey: androidKey,
            userConfirmed: true
        )

        XCTAssertEqual(trustedPeer.deviceId, "android-001")
        XCTAssertEqual(trustedPeer.displayName, "Pixel 8 Pro")
        XCTAssertTrue(store.isTrusted(deviceId: "android-001", publicKey: androidKey))

        // Untrusted peer with same device ID but different key must be rejected
        let fakeKey = "AttackerForgedPublicKeyBytes12345678".data(using: .utf8)!
        XCTAssertFalse(store.isTrusted(deviceId: "android-001", publicKey: fakeKey))

        // Revocation
        let revoked = coordinator.revokePeer(deviceId: "android-001")
        XCTAssertTrue(revoked)
        XCTAssertFalse(store.isTrusted(deviceId: "android-001", publicKey: androidKey))
    }

    func testUserRejectionAbortsPairing() {
        let store = InMemoryTrustedPeerStore()
        let coordinator = PairingCoordinator(store: store)
        let macKey = "MacPublicKeyData".data(using: .utf8)!
        let androidKey = "AndroidPublicKeyData".data(using: .utf8)!

        let payload = coordinator.createChallenge(
            deviceId: "mac-001",
            displayName: "Mac",
            publicKey: macKey
        )

        XCTAssertThrowsError(
            try coordinator.completePairing(
                pairingId: payload.pairingId,
                peerDeviceId: "android-001",
                peerDisplayName: "Pixel",
                peerPublicKey: androidKey,
                userConfirmed: false
            )
        ) { error in
            XCTAssertEqual(error as? PairingError, .userRejected)
        }

        XCTAssertFalse(store.isTrusted(deviceId: "android-001", publicKey: androidKey))
    }

    func testExpiredChallengeRejection() {
        let store = InMemoryTrustedPeerStore()
        let coordinator = PairingCoordinator(store: store)
        let macKey = "MacKey".data(using: .utf8)!
        let androidKey = "AndroidKey".data(using: .utf8)!

        let payload = coordinator.createChallenge(
            deviceId: "mac-001",
            displayName: "Mac",
            publicKey: macKey,
            ttlSeconds: -10 // expired in the past
        )

        XCTAssertTrue(payload.isExpired)

        XCTAssertThrowsError(
            try coordinator.completePairing(
                pairingId: payload.pairingId,
                peerDeviceId: "android-001",
                peerDisplayName: "Pixel",
                peerPublicKey: androidKey,
                userConfirmed: true
            )
        ) { error in
            XCTAssertEqual(error as? PairingError, .challengeExpired)
        }
    }
}
