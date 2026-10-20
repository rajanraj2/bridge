package dev.bridge.network

import dev.bridge.domain.model.ConnectionState
import dev.bridge.domain.model.Platform
import dev.bridge.domain.statemachine.ConnectionStateMachine
import dev.bridge.network.discovery.DiscoveredPeer
import dev.bridge.network.discovery.PeerDiscoveryService
import dev.bridge.network.session.SessionException
import dev.bridge.network.session.SessionManager
import dev.bridge.protocol.v1.Envelope
import dev.bridge.protocol.v1.Hello
import dev.bridge.protocol.v1.Version
import dev.bridge.security.InMemoryTrustedPeerStore
import dev.bridge.security.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionManagerTest {

    @Test
    fun testSuccessfulHelloNegotiationAndHeartbeats() {
        val store = InMemoryTrustedPeerStore()
        val sm = ConnectionStateMachine()
        val serverManager = SessionManager(
            localDeviceId = "android-001",
            localDisplayName = "Pixel 8 Pro",
            localPlatform = Platform.ANDROID,
            peerStore = store,
            stateMachine = sm
        )

        val macKey = "MacPublicKeyBytes".toByteArray()
        store.savePeer(TrustedPeer("mac-001", "MacBook Pro", macKey))

        // Create client Hello
        val clientManager = SessionManager(
            localDeviceId = "mac-001",
            localDisplayName = "MacBook Pro",
            localPlatform = Platform.MACOS,
            peerStore = store,
            stateMachine = ConnectionStateMachine()
        )
        val helloEnv = clientManager.createHelloEnvelope("sess-777")

        // Server processes Hello
        val ackEnv = serverManager.processIncomingHello(helloEnv, macKey)
        assertTrue(serverManager.isEstablished)
        assertEquals("sess-777", serverManager.currentSession?.sessionId)
        assertEquals(ConnectionState.CONNECTED, sm.state)

        assertTrue(ackEnv.hasHelloAck())
        assertEquals(1, ackEnv.helloAck.selectedVersion.major)

        // Heartbeats
        val hb1 = serverManager.createHeartbeatEnvelope()
        val hb2 = serverManager.createHeartbeatEnvelope()
        assertTrue(hb2.sequenceNumber > hb1.sequenceNumber)
        assertEquals("sess-777", hb2.sessionId)

        serverManager.processIncomingEnvelope(hb1)
        assertFalse(serverManager.checkStaleSession(10_000L))
    }

    @Test
    fun testUnauthorizedPeerRejected() {
        val store = InMemoryTrustedPeerStore()
        val sm = ConnectionStateMachine()
        val serverManager = SessionManager(
            localDeviceId = "android-001",
            localDisplayName = "Pixel 8",
            peerStore = store,
            stateMachine = sm
        )

        val clientManager = SessionManager(
            localDeviceId = "rogue-001",
            localDisplayName = "Rogue Device",
            peerStore = store,
            stateMachine = ConnectionStateMachine()
        )
        val helloEnv = clientManager.createHelloEnvelope("sess-rogue")

        val rogueKey = "RogueKey".toByteArray()
        try {
            serverManager.processIncomingHello(helloEnv, rogueKey)
            fail("Expected UnauthorizedPeer exception")
        } catch (e: SessionException.UnauthorizedPeer) {
            assertEquals("rogue-001", e.deviceId)
        }

        assertFalse(serverManager.isEstablished)
        assertEquals(ConnectionState.DISCONNECTED, sm.state)
    }

    @Test
    fun testIncompatibleProtocolVersionRejected() {
        val store = InMemoryTrustedPeerStore()
        val sm = ConnectionStateMachine()
        val serverManager = SessionManager(
            localDeviceId = "android-001",
            localDisplayName = "Pixel",
            peerStore = store,
            stateMachine = sm
        )

        val macKey = "MacKey".toByteArray()
        store.savePeer(TrustedPeer("mac-001", "MacBook", macKey))

        val incompatibleVersion = Version.newBuilder()
            .setMajor(99)
            .setMinor(0)
            .build()

        val hello = Hello.newBuilder()
            .setDeviceId("mac-001")
            .setDisplayName("MacBook")
            .setPlatform("macos")
            .addSupportedVersions(incompatibleVersion)
            .build()

        val env = Envelope.newBuilder()
            .setMessageId("msg-1")
            .setSessionId("sess-1")
            .setSenderDeviceId("mac-001")
            .setHello(hello)
            .build()

        try {
            serverManager.processIncomingHello(env, macKey)
            fail("Expected UnsupportedVersion exception")
        } catch (e: SessionException.UnsupportedVersion) {
            // Success
        }
        assertFalse(serverManager.isEstablished)
    }

    @Test
    fun testDiscoveryPeerTracking() {
        val discovery = PeerDiscoveryService()
        val peer = DiscoveredPeer("p1", "MacBook", "192.168.1.10", 54884, 1, 0)

        discovery.addDiscoveredPeer(peer)
        assertEquals(1, discovery.getDiscoveredPeers().size)
        assertEquals("MacBook", discovery.getDiscoveredPeers()[0].displayName)

        discovery.removeDiscoveredPeer("p1")
        assertEquals(0, discovery.getDiscoveredPeers().size)
    }
}
