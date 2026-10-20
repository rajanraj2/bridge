package dev.bridge.network.socket

import dev.bridge.protocol.WireFrame
import dev.bridge.protocol.v1.Envelope
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

interface SocketConnectionListener {
    fun onConnected(remoteAddress: String)
    fun onEnvelopeReceived(envelope: Envelope)
    fun onDisconnected(reason: String)
}

class BridgeConnection(
    private val socket: Socket,
    private val listener: SocketConnectionListener
) : Closeable {
    private val isRunning = AtomicBoolean(true)
    private var outputStream: OutputStream? = null

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = 0 // Infinite read timeout, heartbeats manage health
    }

    fun startListening() {
        val thread = Thread({
            try {
                val input: InputStream = BufferedInputStream(socket.getInputStream())
                outputStream = BufferedOutputStream(socket.getOutputStream())
                listener.onConnected(socket.inetAddress.hostAddress ?: "unknown")

                while (isRunning.get() && !socket.isClosed) {
                    val frameBytes = WireFrame.readFrame(input)
                    val envelope = Envelope.parseFrom(frameBytes)
                    listener.onEnvelopeReceived(envelope)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    listener.onDisconnected(e.message ?: "Connection closed")
                }
            } finally {
                close()
            }
        }, "BridgeConnection-Worker")
        thread.isDaemon = true
        thread.start()
    }

    @Synchronized
    fun sendEnvelope(envelope: Envelope): Boolean {
        return try {
            val out = outputStream ?: return false
            WireFrame.writeFrame(out, envelope.toByteArray())
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    override fun close() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    val isConnected: Boolean
        get() = isRunning.get() && socket.isConnected && !socket.isClosed
}

class BridgeSocketServer(
    val port: Int = 54884,
    private val connectionListener: SocketConnectionListener
) : Closeable {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val activeConnections = java.util.concurrent.ConcurrentHashMap<String, BridgeConnection>()

    val activePeerCount: Int
        get() = activeConnections.size
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            val server = ServerSocket(port)
            server.reuseAddress = true
            serverSocket = server

            val thread = Thread({
                while (isRunning.get() && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        val remoteAddr = clientSocket.inetAddress?.hostAddress ?: "unknown"
                        val connection = BridgeConnection(clientSocket, connectionListener)
                        activeConnections[remoteAddr] = connection
                        connection.startListening()
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            }, "BridgeSocketServer-Acceptor")
            thread.isDaemon = true
            thread.start()
        }
    }

    fun sendEnvelope(envelope: Envelope): Boolean {
        var anySent = false
        val dead = mutableListOf<String>()
        for ((addr, conn) in activeConnections) {
            if (conn.isConnected) {
                if (conn.sendEnvelope(envelope)) {
                    anySent = true
                }
            } else {
                dead.add(addr)
            }
        }
        dead.forEach { activeConnections.remove(it)?.close() }
        return anySent
    }

    override fun close() {
        if (isRunning.compareAndSet(true, false)) {
            for ((_, conn) in activeConnections) {
                conn.close()
            }
            activeConnections.clear()
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
        }
    }

    val hasActiveConnection: Boolean
        get() = activeConnections.any { it.value.isConnected }
    companion object {
        /**
         * Discovers real local IPv4 addresses (Wi-Fi, Ethernet, or Tailscale)
         */
        fun getLocalIpAddresses(): List<String> {
            val ips = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
                for (iface in interfaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            ips.add(addr.hostAddress)
                        }
                    }
                }
            } catch (_: Exception) {}
            if (ips.isEmpty()) {
                ips.add("127.0.0.1")
            }
            return ips
        }
    }
}

class BridgeSocketClient(
    private val connectionListener: SocketConnectionListener
) : Closeable {
    private var connection: BridgeConnection? = null

    fun connect(host: String, port: Int = 54884, timeoutMs: Int = 5000): Boolean {
        return try {
            close()
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            val conn = BridgeConnection(socket, connectionListener)
            connection = conn
            conn.startListening()
            true
        } catch (e: Exception) {
            connectionListener.onDisconnected("Connection failed to $host:$port: ${e.message}")
            false
        }
    }

    fun sendEnvelope(envelope: Envelope): Boolean {
        return connection?.sendEnvelope(envelope) ?: false
    }

    override fun close() {
        connection?.close()
        connection = null
    }

    val isConnected: Boolean
        get() = connection?.isConnected == true
}
