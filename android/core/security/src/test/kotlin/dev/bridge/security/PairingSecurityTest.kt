package dev.bridge.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingSecurityTest {

    @Test
    fun testPairingChallengeAndSASFlow() {
        val store = InMemoryTrustedPeerStore()
        val coordinator = PairingCoordinator(store)

        val androidKey = "AndroidPublicKeyBytesEd25519SampleData2".toByteArray(Charsets.UTF_8)
        val macKey = "MacPublicKeyBytesEd25519SampleData1".toByteArray(Charsets.UTF_8)

        val challengePayload = coordinator.createChallenge(
            deviceId = "android-001",
            displayName = "Pixel 8 Pro",
            publicKey = androidKey,
            port = 54884,
            ttlSeconds = 60
        )

        assertFalse(challengePayload.isExpired)
        assertEquals("android-001", challengePayload.deviceId)
        assertNotNull(challengePayload.publicKey)
        assertNotNull(challengePayload.challenge)

        // Mac scans challenge and computes SAS
        val androidSAS = coordinator.computeSAS(
            localIdentity = androidKey,
            peerIdentity = macKey,
            challenge = challengePayload.challenge
        )

        val macSAS = coordinator.computeSAS(
            localIdentity = macKey,
            peerIdentity = androidKey,
            challenge = challengePayload.challenge
        )

        assertEquals("SAS codes on both devices must match exactly", androidSAS, macSAS)

        // Complete pairing
        val trustedPeer = coordinator.completePairing(
            pairingId = challengePayload.pairingId,
            peerDeviceId = "mac-001",
            peerDisplayName = "MacBook Pro",
            peerPublicKey = macKey,
            userConfirmed = true
        )

        assertEquals("mac-001", trustedPeer.deviceId)
        assertTrue(store.isTrusted("mac-001", macKey))

        // Untrusted peer with same ID but modified key is rejected
        val fakeKey = "AttackerModifiedKeyBytes".toByteArray()
        assertFalse(store.isTrusted("mac-001", fakeKey))

        // Revocation
        val revoked = coordinator.revokePeer("mac-001")
        assertTrue(revoked)
        assertFalse(store.isTrusted("mac-001", macKey))
    }

    @Test
    fun testUserRejectionAbortsPairing() {
        val store = InMemoryTrustedPeerStore()
        val coordinator = PairingCoordinator(store)

        val androidKey = "AndroidKey".toByteArray()
        val macKey = "MacKey".toByteArray()

        val challengePayload = coordinator.createChallenge(
            deviceId = "android-001",
            displayName = "Pixel",
            publicKey = androidKey
        )

        try {
            coordinator.completePairing(
                pairingId = challengePayload.pairingId,
                peerDeviceId = "mac-001",
                peerDisplayName = "Mac",
                peerPublicKey = macKey,
                userConfirmed = false
            )
            fail("Expected UserRejected exception")
        } catch (e: PairingException.UserRejected) {
            // Expected
        }

        assertFalse(store.isTrusted("mac-001", macKey))
    }

    @Test
    fun testExpiredChallengeThrowsException() {
        val store = InMemoryTrustedPeerStore()
        val coordinator = PairingCoordinator(store)

        val androidKey = "AndroidKey".toByteArray()
        val macKey = "MacKey".toByteArray()

        val payload = coordinator.createChallenge(
            deviceId = "android-001",
            displayName = "Pixel",
            publicKey = androidKey,
            ttlSeconds = -10 // expired in the past
        )

        assertTrue(payload.isExpired)

        try {
            coordinator.completePairing(
                pairingId = payload.pairingId,
                peerDeviceId = "mac-001",
                peerDisplayName = "Mac",
                peerPublicKey = macKey,
                userConfirmed = true
            )
            fail("Expected ChallengeExpired exception")
        } catch (e: PairingException.ChallengeExpired) {
            // Expected
        }
    }

    @Test
    fun testJsonPayloadSerializationDeserialization() {
        val store = InMemoryTrustedPeerStore()
        val coordinator = PairingCoordinator(store)
        val key = "TestKeyBytes".toByteArray()

        val payload = coordinator.createChallenge("dev-1", "Test Device", key, 54884, 120)
        val json = payload.toJsonString()

        val parsed = PairingChallengePayload.fromJsonString(json)
        assertEquals(payload.pairingId, parsed.pairingId)
        assertEquals(payload.deviceId, parsed.deviceId)
        assertEquals(payload.displayName, parsed.displayName)
        assertEquals(payload.port, parsed.port)
        assertEquals(payload.expiresAtUnixMs, parsed.expiresAtUnixMs)
    }
}
