package dev.bridge.network.socket

import dev.bridge.protocol.v1.Envelope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object BridgeConnectionManager {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BridgeConnectionManager-Worker").apply { isDaemon = true }
    }

    var activeServer: BridgeSocketServer? = null
        private set
    var activeClient: BridgeSocketClient? = null
        private set
    var activeRemoteAddress: String? = null
        private set

    val isConnected: Boolean
        get() = (activeServer?.hasActiveConnection == true) || (activeClient?.isConnected == true)

    private val listeners = CopyOnWriteArrayList<SocketConnectionListener>()

    fun addListener(listener: SocketConnectionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SocketConnectionListener) {
        listeners.remove(listener)
    }

    private val internalListener = object : SocketConnectionListener {
        override fun onConnected(remoteAddress: String) {
            println("BridgeApp: onConnected to $remoteAddress")
            activeRemoteAddress = remoteAddress
            listeners.forEach { it.onConnected(remoteAddress) }
        }

        override fun onEnvelopeReceived(envelope: Envelope) {
            println("BridgeApp: onEnvelopeReceived payloadCase=${envelope.payloadCase}")
            listeners.forEach { it.onEnvelopeReceived(envelope) }
        }

        override fun onDisconnected(reason: String) {
            println("BridgeApp: onDisconnected reason=$reason")
            activeRemoteAddress = null
            listeners.forEach { it.onDisconnected(reason) }
        }
    }

    @Synchronized
    fun startServer(port: Int = 54884) {
        if (activeServer == null) {
            val server = BridgeSocketServer(port, internalListener)
            server.start()
            activeServer = server
        }
    }

    @Synchronized
    fun stopServer() {
        activeServer?.close()
        activeServer = null
    }

    fun connectToMac(host: String, port: Int = 54884, onResult: (Boolean) -> Unit) {
        executor.execute {
            try {
                activeClient?.close()
                val client = BridgeSocketClient(internalListener)
                val success = client.connect(host, port)
                if (success) {
                    activeClient = client
                    activeRemoteAddress = host
                }
                onResult(success)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }

    fun disconnectClient() {
        executor.execute {
            activeClient?.close()
            activeClient = null
            activeRemoteAddress = null
        }
    }

    /**
     * Sends a protobuf envelope asynchronously on a background worker thread.
     * Guaranteed to NEVER throw NetworkOnMainThreadException.
     */
    fun sendEnvelope(envelope: Envelope, onComplete: ((Boolean) -> Unit)? = null) {
        executor.execute {
            var sent = false
            try {
                val hasServer = activeServer?.hasActiveConnection == true
                val hasClient = activeClient?.isConnected == true
                println("BridgeApp: sendEnvelope called. hasServer=$hasServer, hasClient=$hasClient, payload=${envelope.payloadCase}")

                if (hasServer) {
                    sent = activeServer?.sendEnvelope(envelope) == true
                    println("BridgeApp: send via server result=$sent")
                }
                if (!sent && hasClient) {
                    sent = activeClient?.sendEnvelope(envelope) == true
                    println("BridgeApp: send via client result=$sent")
                }
            } catch (e: Exception) {
                println("BridgeApp: sendEnvelope exception: ${e.message}")
                sent = false
            }
            onComplete?.invoke(sent)
        }
    }

    fun closeAll() {
        executor.execute {
            activeClient?.close()
            activeClient = null
            activeServer?.close()
            activeServer = null
            activeRemoteAddress = null
        }
    }
}
