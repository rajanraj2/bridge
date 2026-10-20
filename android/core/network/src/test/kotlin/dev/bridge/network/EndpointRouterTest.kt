package dev.bridge.network

import dev.bridge.network.discovery.DiscoveredPeer
import dev.bridge.network.discovery.PeerDiscoveryService
import dev.bridge.network.endpoint.ConnectionRouteType
import dev.bridge.network.endpoint.EndpointRouter
import dev.bridge.network.endpoint.InMemoryRemoteEndpointStore
import dev.bridge.network.endpoint.RemoteEndpointConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointRouterTest {

    @Test
    fun testLocalFirstSelection() {
        val discovery = PeerDiscoveryService()
        val store = InMemoryRemoteEndpointStore()
        val router = EndpointRouter(discovery, store)

        // Both local mDNS and remote Tailscale are configured
        discovery.addDiscoveredPeer(DiscoveredPeer("mac-001", "MacBook", "192.168.1.150", 54884, 1, 0))
        store.saveEndpoint(RemoteEndpointConfig("mac-001", "100.101.102.103", 54884, isEnabled = true))

        val route = router.resolveRoute("mac-001")
        assertNotNull(route)
        assertEquals("Local LAN must take precedence when available", ConnectionRouteType.LOCAL_LAN, route?.routeType)
        assertEquals("192.168.1.150", route?.host)
    }

    @Test
    fun testTailscaleFallbackWhenLocalDiscoveryFails() {
        val discovery = PeerDiscoveryService()
        val store = InMemoryRemoteEndpointStore()
        val router = EndpointRouter(discovery, store)

        // Only remote Tailscale is configured
        store.saveEndpoint(RemoteEndpointConfig("mac-001", "my-mac.magicdns.net", 54884, isEnabled = true))

        val route = router.resolveRoute("mac-001")
        assertNotNull(route)
        assertEquals("Must fall back to Remote Tailscale when local peer is not discovered", ConnectionRouteType.REMOTE_TAILSCALE, route?.routeType)
        assertEquals("my-mac.magicdns.net", route?.host)
    }

    @Test
    fun testDisabledEndpointReturnsNull() {
        val discovery = PeerDiscoveryService()
        val store = InMemoryRemoteEndpointStore()
        val router = EndpointRouter(discovery, store)

        store.saveEndpoint(RemoteEndpointConfig("mac-001", "100.1.2.3", 54884, isEnabled = false))
        assertNull(router.resolveRoute("mac-001"))
    }
}
