package dev.bridge.network.session

import dev.bridge.domain.model.ConnectionState
import dev.bridge.domain.model.DeviceIdentity
import dev.bridge.domain.model.PeerSession
import dev.bridge.domain.model.Platform
import dev.bridge.domain.statemachine.ConnectionEvent
import dev.bridge.domain.statemachine.ConnectionStateMachine
import dev.bridge.protocol.v1.Envelope
import dev.bridge.protocol.v1.Heartbeat
import dev.bridge.protocol.v1.Hello
import dev.bridge.protocol.v1.HelloAck
import dev.bridge.protocol.v1.Version
import dev.bridge.security.IdentityManager
import dev.bridge.security.TrustedPeerStorage
import java.util.UUID

sealed class SessionException(message: String) : Exception(message) {
    class UnsupportedVersion : SessionException("Incompatible protocol version")
    class UnauthorizedPeer(val deviceId: String) : SessionException("Unauthorized peer identity: $deviceId")
    class HandshakeFailed(reason: String) : SessionException("Handshake failed: $reason")
    class SessionTimeout : SessionException("Session timed out due to missing heartbeats")
}

interface SessionListener {
    fun onSessionEstablished(session: PeerSession)
    fun onEnvelopeReceived(envelope: Envelope)
    fun onSessionFailed(error: SessionException)
    fun onSessionClosed()
}

class SessionManager(
    val localDeviceId: String,
    val localDisplayName: String,
    val localPlatform: Platform = Platform.ANDROID,
    private val peerStore: TrustedPeerStorage,
    private val stateMachine: ConnectionStateMachine
) {
    companion object {
        const val CURRENT_PROTOCOL_MAJOR = 1
        const val CURRENT_PROTOCOL_MINOR = 0
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 45_000L
    }

    var currentSession: PeerSession? = null
        private set

    var sequenceNumber: Long = 0
        private set

    var lastActivityTimeMs: Long = System.currentTimeMillis()
        private set

    var isEstablished: Boolean = false
        private set

    private val listeners = mutableListOf<SessionListener>()

    @Synchronized
    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun createHelloEnvelope(sessionId: String): Envelope {
        sequenceNumber++
        val version = Version.newBuilder()
            .setMajor(CURRENT_PROTOCOL_MAJOR)
            .setMinor(CURRENT_PROTOCOL_MINOR)
            .build()

        val hello = Hello.newBuilder()
            .setDeviceId(localDeviceId)
            .setDisplayName(localDisplayName)
            .setPlatform(localPlatform.value)
            .addSupportedVersions(version)
            .addAllCapabilities(listOf("clipboard", "notifications", "transfers"))
            .build()

        return Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(localDeviceId)
            .setSequenceNumber(sequenceNumber)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(CURRENT_PROTOCOL_MAJOR)
            .setProtocolMinor(CURRENT_PROTOCOL_MINOR)
            .setHello(hello)
            .build()
    }

    @Synchronized
    fun createHelloAckEnvelope(sessionId: String, acceptedCapabilities: List<String>): Envelope {
        sequenceNumber++
        val version = Version.newBuilder()
            .setMajor(CURRENT_PROTOCOL_MAJOR)
            .setMinor(CURRENT_PROTOCOL_MINOR)
            .build()

        val ack = HelloAck.newBuilder()
            .setSelectedVersion(version)
            .setSessionId(sessionId)
            .addAllAcceptedCapabilities(acceptedCapabilities)
            .build()

        return Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(localDeviceId)
            .setSequenceNumber(sequenceNumber)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(CURRENT_PROTOCOL_MAJOR)
            .setProtocolMinor(CURRENT_PROTOCOL_MINOR)
            .setHelloAck(ack)
            .build()
    }

    @Synchronized
    fun processIncomingHello(envelope: Envelope, peerPublicKey: ByteArray): Envelope {
        if (!envelope.hasHello()) {
            throw SessionException.HandshakeFailed("Expected Hello envelope")
        }
        val hello = envelope.hello

        // Check version compatibility
        val isCompatible = hello.supportedVersionsList.any { it.major == CURRENT_PROTOCOL_MAJOR }
        if (!isCompatible) {
            stateMachine.handle(ConnectionEvent.PairingFailed("Unsupported protocol version"))
            throw SessionException.UnsupportedVersion()
        }

        // Verify peer identity
        val isTrusted = peerStore.isTrusted(hello.deviceId, peerPublicKey)
        if (!isTrusted) {
            stateMachine.handle(ConnectionEvent.Disconnect)
            throw SessionException.UnauthorizedPeer(hello.deviceId)
        }

        val peerDevice = DeviceIdentity(
            deviceId = hello.deviceId,
            displayName = hello.displayName,
            platform = if (hello.platform == "macos") Platform.MACOS else Platform.ANDROID,
            publicKeyFingerprint = IdentityManager.fingerprint(peerPublicKey)
        )

        val session = PeerSession(
            sessionId = envelope.sessionId,
            peerDevice = peerDevice,
            protocolMajor = CURRENT_PROTOCOL_MAJOR,
            protocolMinor = CURRENT_PROTOCOL_MINOR
        )

        currentSession = session
        isEstablished = true
        lastActivityTimeMs = System.currentTimeMillis()
        stateMachine.handle(ConnectionEvent.ConnectionEstablished(session))
        listeners.forEach { it.onSessionEstablished(session) }

        return createHelloAckEnvelope(envelope.sessionId, hello.capabilitiesList)
    }

    @Synchronized
    fun processIncomingHelloAck(envelope: Envelope, peerDevice: DeviceIdentity, peerPublicKey: ByteArray) {
        if (!envelope.hasHelloAck()) {
            throw SessionException.HandshakeFailed("Expected HelloAck envelope")
        }
        val ack = envelope.helloAck
        if (ack.selectedVersion.major != CURRENT_PROTOCOL_MAJOR) {
            throw SessionException.UnsupportedVersion()
        }

        val isTrusted = peerStore.isTrusted(peerDevice.deviceId, peerPublicKey)
        if (!isTrusted) {
            stateMachine.handle(ConnectionEvent.Disconnect)
            throw SessionException.UnauthorizedPeer(peerDevice.deviceId)
        }

        val session = PeerSession(
            sessionId = envelope.sessionId,
            peerDevice = peerDevice,
            protocolMajor = ack.selectedVersion.major,
            protocolMinor = ack.selectedVersion.minor
        )

        currentSession = session
        isEstablished = true
        lastActivityTimeMs = System.currentTimeMillis()
        stateMachine.handle(ConnectionEvent.ConnectionEstablished(session))
        listeners.forEach { it.onSessionEstablished(session) }
    }

    @Synchronized
    fun createHeartbeatEnvelope(): Envelope {
        sequenceNumber++
        val now = System.currentTimeMillis()
        val heartbeat = Heartbeat.newBuilder()
            .setSentAtUnixMs(now)
            .build()

        lastActivityTimeMs = now
        return Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(currentSession?.sessionId ?: "")
            .setSenderDeviceId(localDeviceId)
            .setSequenceNumber(sequenceNumber)
            .setCreatedAtUnixMs(now)
            .setProtocolMajor(CURRENT_PROTOCOL_MAJOR)
            .setProtocolMinor(CURRENT_PROTOCOL_MINOR)
            .setHeartbeat(heartbeat)
            .build()
    }

    @Synchronized
    fun processIncomingEnvelope(envelope: Envelope) {
        lastActivityTimeMs = System.currentTimeMillis()
        if (envelope.hasHeartbeat()) {
            return
        }
        listeners.forEach { it.onEnvelopeReceived(envelope) }
    }

    @Synchronized
    fun checkStaleSession(timeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS): Boolean {
        if (!isEstablished) return false
        val elapsed = System.currentTimeMillis() - lastActivityTimeMs
        if (elapsed > timeoutMs) {
            isEstablished = false
            currentSession = null
            stateMachine.handle(ConnectionEvent.ConnectionLost(temporary = true))
            val error = SessionException.SessionTimeout()
            listeners.forEach { it.onSessionFailed(error) }
            return true
        }
        return false
    }

    @Synchronized
    fun closeSession() {
        isEstablished = false
        currentSession = null
        stateMachine.handle(ConnectionEvent.Disconnect)
        listeners.forEach { it.onSessionClosed() }
    }
}
