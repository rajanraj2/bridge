package dev.bridge.security

data class TrustedPeer(
    val deviceId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val publicKeyFingerprint: String = IdentityManager.fingerprint(publicKey),
    val pairedAtUnixMs: Long = System.currentTimeMillis(),
    val lastSeenAtUnixMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrustedPeer
        return deviceId == other.deviceId && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

interface TrustedPeerStorage {
    fun isTrusted(deviceId: String, publicKey: ByteArray): Boolean
    fun getPeer(deviceId: String): TrustedPeer?
    fun savePeer(peer: TrustedPeer)
    fun revokePeer(deviceId: String): Boolean
    fun allPeers(): List<TrustedPeer>
    fun clear()
}

class InMemoryTrustedPeerStore : TrustedPeerStorage {
    private val peers = mutableMapOf<String, TrustedPeer>()

    @Synchronized
    override fun isTrusted(deviceId: String, publicKey: ByteArray): Boolean {
        val peer = peers[deviceId] ?: return false
        return peer.publicKey.contentEquals(publicKey)
    }

    @Synchronized
    override fun getPeer(deviceId: String): TrustedPeer? {
        return peers[deviceId]
    }

    @Synchronized
    override fun savePeer(peer: TrustedPeer) {
        peers[peer.deviceId] = peer
    }

    @Synchronized
    override fun revokePeer(deviceId: String): Boolean {
        return peers.remove(deviceId) != null
    }

    @Synchronized
    override fun allPeers(): List<TrustedPeer> {
        return peers.values.toList()
    }

    @Synchronized
    override fun clear() {
        peers.clear()
    }
}
