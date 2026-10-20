package dev.bridge.network.endpoint

import dev.bridge.network.discovery.PeerDiscoveryService

enum class ConnectionRouteType {
    LOCAL_LAN,
    REMOTE_TAILSCALE
}

data class RemoteEndpointConfig(
    val deviceId: String,
    var tailscaleHost: String,
    var port: Int = 54884,
    var isEnabled: Boolean = true
) {
    val isValid: Boolean
        get() = tailscaleHost.isNotBlank() && port in 1..65535
}

interface RemoteEndpointStorage {
    fun getEndpoint(deviceId: String): RemoteEndpointConfig?
    fun saveEndpoint(config: RemoteEndpointConfig)
    fun removeEndpoint(deviceId: String)
    fun allEndpoints(): List<RemoteEndpointConfig>
}

class InMemoryRemoteEndpointStore : RemoteEndpointStorage {
    private val endpoints = mutableMapOf<String, RemoteEndpointConfig>()

    @Synchronized
    override fun getEndpoint(deviceId: String): RemoteEndpointConfig? {
        return endpoints[deviceId]
    }

    @Synchronized
    override fun saveEndpoint(config: RemoteEndpointConfig) {
        endpoints[config.deviceId] = config
    }

    @Synchronized
    override fun removeEndpoint(deviceId: String) {
        endpoints.remove(deviceId)
    }

    @Synchronized
    override fun allEndpoints(): List<RemoteEndpointConfig> {
        return endpoints.values.toList()
    }
}

data class ResolvedRoute(
    val deviceId: String,
    val host: String,
    val port: Int,
    val routeType: ConnectionRouteType
)

class EndpointRouter(
    private val discoveryService: PeerDiscoveryService,
    private val endpointStore: RemoteEndpointStorage
) {
    /**
     * Resolves the connection route for a target device:
     * 1. Checks local mDNS discovery first.
     * 2. If not found locally, falls back to configured Tailscale endpoint.
     */
    @Synchronized
    fun resolveRoute(deviceId: String): ResolvedRoute? {
        // 1. Local-first check
        val localPeer = discoveryService.getDiscoveredPeers().find { it.id == deviceId }
        if (localPeer != null) {
            return ResolvedRoute(
                deviceId = deviceId,
                host = localPeer.host,
                port = localPeer.port,
                routeType = ConnectionRouteType.LOCAL_LAN
            )
        }

        // 2. Remote Tailscale fallback
        val remote = endpointStore.getEndpoint(deviceId)
        if (remote != null && remote.isEnabled && remote.isValid) {
            return ResolvedRoute(
                deviceId = deviceId,
                host = remote.tailscaleHost,
                port = remote.port,
                routeType = ConnectionRouteType.REMOTE_TAILSCALE
            )
        }

        return null
    }
}
