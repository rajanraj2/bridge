package dev.bridge.network.discovery

data class DiscoveredPeer(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val isPaired: Boolean = false
)

interface PeerDiscoveryListener {
    fun onPeerDiscovered(peer: DiscoveredPeer)
    fun onPeerLost(peerId: String)
}

class PeerDiscoveryService {
    companion object {
        const val SERVICE_TYPE = "_bridge._tcp."
        const val DEFAULT_PORT = 54884
    }

    private val discovered = mutableMapOf<String, DiscoveredPeer>()
    private val listeners = mutableListOf<PeerDiscoveryListener>()

    @Synchronized
    fun addListener(listener: PeerDiscoveryListener) {
        listeners.add(listener)
        discovered.values.forEach { listener.onPeerDiscovered(it) }
    }

    @Synchronized
    fun removeListener(listener: PeerDiscoveryListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun addDiscoveredPeer(peer: DiscoveredPeer) {
        discovered[peer.id] = peer
        listeners.forEach { it.onPeerDiscovered(peer) }
    }

    @Synchronized
    fun removeDiscoveredPeer(id: String) {
        if (discovered.remove(id) != null) {
            listeners.forEach { it.onPeerLost(id) }
        }
    }

    @Synchronized
    fun getDiscoveredPeers(): List<DiscoveredPeer> {
        return discovered.values.toList()
    }

    @Synchronized
    fun clear() {
        discovered.clear()
    }
}
