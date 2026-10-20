package dev.bridge.network

import dev.bridge.network.socket.BridgeSocketClient
import dev.bridge.network.socket.BridgeSocketServer
import dev.bridge.network.socket.SocketConnectionListener
import dev.bridge.protocol.v1.Envelope
import dev.bridge.protocol.v1.Heartbeat
import dev.bridge.protocol.v1.Hello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SocketCommunicationTest {

    @Test
    fun testLocalSocketCommunication() {
        val testPort = 54888
        val serverReceivedLatch = CountDownLatch(1)
        val clientReceivedLatch = CountDownLatch(1)

        var serverReceivedEnvelope: Envelope? = null
        var clientReceivedEnvelope: Envelope? = null

        val serverListener = object : SocketConnectionListener {
            override fun onConnected(remoteAddress: String) {}
            override fun onEnvelopeReceived(envelope: Envelope) {
                serverReceivedEnvelope = envelope
                serverReceivedLatch.countDown()
            }
            override fun onDisconnected(reason: String) {}
        }

        val clientListener = object : SocketConnectionListener {
            override fun onConnected(remoteAddress: String) {}
            override fun onEnvelopeReceived(envelope: Envelope) {
                clientReceivedEnvelope = envelope
                clientReceivedLatch.countDown()
            }
            override fun onDisconnected(reason: String) {}
        }

        val server = BridgeSocketServer(testPort, serverListener)
        server.start()

        val client = BridgeSocketClient(clientListener)
        try {
            // Give server thread a moment to bind
            Thread.sleep(100)

            val connected = client.connect("127.0.0.1", testPort)
            assertTrue("Client must connect to server", connected)
            Thread.sleep(100)

            // 1. Client sends Hello envelope
            val clientEnv = Envelope.newBuilder()
                .setMessageId("msg-c1")
                .setSessionId("s1")
                .setSenderDeviceId("client-phone")
                .setHello(Hello.newBuilder().setDeviceId("client-phone").build())
                .build()

            val clientSent = client.sendEnvelope(clientEnv)
            assertTrue("Client send must succeed", clientSent)

            assertTrue("Server must receive envelope within timeout", serverReceivedLatch.await(3, TimeUnit.SECONDS))
            assertEquals("msg-c1", serverReceivedEnvelope?.messageId)

            // 2. Server sends Heartbeat response
            val serverEnv = Envelope.newBuilder()
                .setMessageId("msg-s1")
                .setSessionId("s1")
                .setSenderDeviceId("server-mac")
                .setHeartbeat(Heartbeat.newBuilder().setSentAtUnixMs(9999).build())
                .build()

            val serverSent = server.sendEnvelope(serverEnv)
            assertTrue("Server send must succeed", serverSent)

            assertTrue("Client must receive envelope within timeout", clientReceivedLatch.await(3, TimeUnit.SECONDS))
            assertEquals("msg-s1", clientReceivedEnvelope?.messageId)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun testLocalIpAddressDiscovery() {
        val ips = BridgeSocketServer.getLocalIpAddresses()
        assertFalse("Device must have local IP addresses detected", ips.isEmpty())
    }
}
