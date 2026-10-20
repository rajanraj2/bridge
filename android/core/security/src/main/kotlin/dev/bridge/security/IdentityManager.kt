package dev.bridge.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

object IdentityManager {

    fun generateDeviceId(): String {
        return "brg-" + UUID.randomUUID().toString().lowercase()
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun fingerprint(data: ByteArray): String {
        val hash = sha256(data)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Computes a 6-digit Short Authentication String (SAS) from two public identities and a challenge.
     * Arguments are sorted lexicographically to ensure local/remote determinism regardless of caller order.
     */
    fun computeSAS(localIdentity: ByteArray, peerIdentity: ByteArray, challenge: ByteArray): String {
        val sorted = if (compareByteArrays(localIdentity, peerIdentity) <= 0) {
            localIdentity + peerIdentity
        } else {
            peerIdentity + localIdentity
        }
        val combined = sorted + challenge
        val hash = sha256(combined)

        val prefix = ByteBuffer.wrap(hash, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val sasCode = prefix % 1_000_000
        return "%06d".format(sasCode)
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) {
                return byteA - byteB
            }
        }
        return a.size - b.size
    }
}
