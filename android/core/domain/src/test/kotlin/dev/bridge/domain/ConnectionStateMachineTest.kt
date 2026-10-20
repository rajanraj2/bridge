package dev.bridge.domain

import dev.bridge.domain.model.ConnectionState
import dev.bridge.domain.model.DeviceIdentity
import dev.bridge.domain.model.PeerSession
import dev.bridge.domain.model.Platform
import dev.bridge.domain.statemachine.ConnectionEvent
import dev.bridge.domain.statemachine.ConnectionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {

    @Test
    fun testInitialStateIsDisconnected() {
        val sm = ConnectionStateMachine()
        assertEquals(ConnectionState.DISCONNECTED, sm.state)
        assertNull(sm.currentPeer)
        assertNull(sm.currentSession)
    }

    @Test
    fun testSuccessfulPairingAndConnectionLifecycle() {
        val sm = ConnectionStateMachine(maxReconnectAttempts = 3)

        sm.handle(ConnectionEvent.StartDiscovery)
        assertEquals(ConnectionState.DISCOVERING, sm.state)

        val peer = DeviceIdentity(
            deviceId = "mac-001",
            displayName = "MacBook Pro",
            platform = Platform.MACOS,
            publicKeyFingerprint = "11:22:33:44"
        )

        sm.handle(ConnectionEvent.InitiatePairing(peer))
        assertEquals(ConnectionState.PAIRING, sm.state)
        assertEquals(peer, sm.currentPeer)

        sm.handle(ConnectionEvent.PairingSucceeded)
        assertEquals(ConnectionState.CONNECTING, sm.state)

        val session = PeerSession(
            sessionId = "sess-1",
            peerDevice = peer,
            protocolMajor = 1,
            protocolMinor = 0
        )
        sm.handle(ConnectionEvent.ConnectionEstablished(session))
        assertEquals(ConnectionState.CONNECTED, sm.state)
        assertEquals(session, sm.currentSession)

        // Temporary connection loss triggers reconnecting
        sm.handle(ConnectionEvent.ConnectionLost(temporary = true))
        assertEquals(ConnectionState.RECONNECTING, sm.state)
        assertEquals(1, sm.reconnectAttempts)

        // Restoration of connection
        sm.handle(ConnectionEvent.ConnectionEstablished(session))
        assertEquals(ConnectionState.CONNECTED, sm.state)
        assertEquals(0, sm.reconnectAttempts)

        // Explicit disconnect
        sm.handle(ConnectionEvent.Disconnect)
        assertEquals(ConnectionState.DISCONNECTED, sm.state)
        assertNull(sm.currentSession)
        assertNull(sm.currentPeer)
    }

    @Test
    fun testReconnectBudgetExhaustionTransitionsToDiscovering() {
        val sm = ConnectionStateMachine(maxReconnectAttempts = 2)
        val peer = DeviceIdentity("mac-002", "Mac mini", Platform.MACOS, "AA:BB")
        val session = PeerSession("sess-2", peer, 1, 0)

        sm.handle(ConnectionEvent.StartDiscovery)
        sm.handle(ConnectionEvent.ConnectToPeer(peer))
        sm.handle(ConnectionEvent.ConnectionEstablished(session))

        // Lost 1
        sm.handle(ConnectionEvent.ConnectionLost(temporary = true))
        assertEquals(ConnectionState.RECONNECTING, sm.state)
        assertEquals(1, sm.reconnectAttempts)

        // Lost 2
        sm.handle(ConnectionEvent.ConnectionLost(temporary = true))
        assertEquals(ConnectionState.RECONNECTING, sm.state)
        assertEquals(2, sm.reconnectAttempts)

        // Exceeded budget -> falls back to discovering
        sm.handle(ConnectionEvent.ConnectionLost(temporary = true))
        assertEquals(ConnectionState.DISCOVERING, sm.state)
    }

    @Test
    fun testBackoffDelayCalculation() {
        val sm = ConnectionStateMachine(baseReconnectDelaySeconds = 1.0)
        val delay0 = sm.backoffDelaySeconds(0)
        assertTrue(delay0 in 0.7..1.3)

        val delay3 = sm.backoffDelaySeconds(3)
        // 1.0 * 2^3 = 8.0 * (0.8 .. 1.2) = 6.4 .. 9.6
        assertTrue(delay3 in 6.0..10.0)
    }
}
