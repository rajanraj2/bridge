package dev.bridge.security

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

sealed class PairingException(message: String) : Exception(message) {
    class ChallengeExpired : PairingException("Pairing challenge has expired")
    class InvalidChallenge : PairingException("Invalid pairing challenge")
    class UserRejected : PairingException("User rejected pairing confirmation")
}

data class PairingChallengePayload(
    val pairingId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val challengeBase64: String,
    val port: Int,
    val expiresAtUnixMs: Long
) {
    val publicKey: ByteArray
        get() = Base64.getDecoder().decode(publicKeyBase64)

    val challenge: ByteArray
        get() = Base64.getDecoder().decode(challengeBase64)

    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAtUnixMs

    fun toJsonString(): String {
        return """{"pairingId":"$pairingId","deviceId":"$deviceId","displayName":"$displayName","publicKeyBase64":"$publicKeyBase64","challengeBase64":"$challengeBase64","port":$port,"expiresAtUnixMs":$expiresAtUnixMs}"""
    }

    companion object {
        fun fromJsonString(json: String): PairingChallengePayload {
            val pairingId = extractJsonString(json, "pairingId") ?: throw PairingException.InvalidChallenge()
            val deviceId = extractJsonString(json, "deviceId") ?: throw PairingException.InvalidChallenge()
            val displayName = extractJsonString(json, "displayName") ?: throw PairingException.InvalidChallenge()
            val publicKeyBase64 = extractJsonString(json, "publicKeyBase64") ?: throw PairingException.InvalidChallenge()
            val challengeBase64 = extractJsonString(json, "challengeBase64") ?: throw PairingException.InvalidChallenge()
            val port = extractJsonInt(json, "port") ?: 54884
            val expiresAtUnixMs = extractJsonLong(json, "expiresAtUnixMs") ?: throw PairingException.InvalidChallenge()

            return PairingChallengePayload(
                pairingId = pairingId,
                deviceId = deviceId,
                displayName = displayName,
                publicKeyBase64 = publicKeyBase64,
                challengeBase64 = challengeBase64,
                port = port,
                expiresAtUnixMs = expiresAtUnixMs
            )
        }

        private fun extractJsonString(json: String, key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return pattern.find(json)?.groupValues?.get(1)
        }

        private fun extractJsonInt(json: String, key: String): Int? {
            val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
        }

        private fun extractJsonLong(json: String, key: String): Long? {
            val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
            return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
        }
    }
}

class PairingCoordinator(
    private val store: TrustedPeerStorage
) {
    private val secureRandom = SecureRandom()
    private val activeChallenges = mutableMapOf<String, PairingChallengePayload>()

    @Synchronized
    fun createChallenge(
        deviceId: String,
        displayName: String,
        publicKey: ByteArray,
        port: Int = 54884,
        ttlSeconds: Int = 120
    ): PairingChallengePayload {
        val pairingId = UUID.randomUUID().toString().lowercase()
        val challengeBytes = ByteArray(32)
        secureRandom.nextBytes(challengeBytes)

        val expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L)
        val payload = PairingChallengePayload(
            pairingId = pairingId,
            deviceId = deviceId,
            displayName = displayName,
            publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey),
            challengeBase64 = Base64.getEncoder().encodeToString(challengeBytes),
            port = port,
            expiresAtUnixMs = expiresAt
        )
        activeChallenges[pairingId] = payload
        return payload
    }

    fun computeSAS(localIdentity: ByteArray, peerIdentity: ByteArray, challenge: ByteArray): String {
        return IdentityManager.computeSAS(localIdentity, peerIdentity, challenge)
    }

    @Synchronized
    fun completePairing(
        pairingId: String,
        peerDeviceId: String,
        peerDisplayName: String,
        peerPublicKey: ByteArray,
        userConfirmed: Boolean
    ): TrustedPeer {
        if (!userConfirmed) {
            activeChallenges.remove(pairingId)
            throw PairingException.UserRejected()
        }

        val challenge = activeChallenges.remove(pairingId) ?: throw PairingException.InvalidChallenge()
        if (challenge.isExpired) {
            throw PairingException.ChallengeExpired()
        }

        val trustedPeer = TrustedPeer(
            deviceId = peerDeviceId,
            displayName = peerDisplayName,
            publicKey = peerPublicKey
        )
        store.savePeer(trustedPeer)
        return trustedPeer
    }

    @Synchronized
    fun revokePeer(deviceId: String): Boolean {
        return store.revokePeer(deviceId)
    }
}
