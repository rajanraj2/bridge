package dev.bridge.protocol

import com.google.protobuf.util.JsonFormat
import dev.bridge.protocol.v1.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

class ProtocolFixtureTest {

    private fun loadFixtureJson(fileName: String): String {
        // Resolve fixtures directory by walking up from current directory
        var dir = Paths.get("").toAbsolutePath().toFile()
        while (dir != null) {
            val candidate = File(dir, "protocol/fixtures/$fileName")
            if (candidate.exists()) {
                return candidate.readText()
            }
            dir = dir.parentFile
        }
        val fallback = File("/home/zorax/zrx/bridge/protocol/fixtures/$fileName")
        if (fallback.exists()) return fallback.readText()
        throw IllegalArgumentException("Fixture $fileName not found")
    }

    private fun parseEnvelope(json: String): Envelope {
        val builder = Envelope.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(json, builder)
        return builder.build()
    }

    @Test
    fun testDecodeHelloFixture() {
        val json = loadFixtureJson("hello.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-001", envelope.messageId)
        assertEquals("sess-alpha-001", envelope.sessionId)
        assertEquals("dev-mac-12345", envelope.senderDeviceId)
        assertEquals(1L, envelope.sequenceNumber)
        assertEquals(1, envelope.protocolMajor)
        assertEquals(0, envelope.protocolMinor)

        assertTrue(envelope.hasHello())
        val hello = envelope.hello
        assertEquals("dev-mac-12345", hello.deviceId)
        assertEquals("MacBook Pro", hello.displayName)
        assertEquals("macos", hello.platform)
        assertEquals(1, hello.supportedVersionsCount)
        assertEquals(1, hello.getSupportedVersions(0).major)
        assertEquals(listOf("clipboard", "notifications", "transfers"), hello.capabilitiesList)
    }

    @Test
    fun testDecodeHelloAckFixture() {
        val json = loadFixtureJson("hello_ack.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-002", envelope.messageId)
        assertTrue(envelope.hasHelloAck())
        val ack = envelope.helloAck
        assertEquals(1, ack.selectedVersion.major)
        assertEquals("sess-alpha-001", ack.sessionId)
        assertEquals(listOf("clipboard", "notifications", "transfers"), ack.acceptedCapabilitiesList)
    }

    @Test
    fun testDecodeHeartbeatFixture() {
        val json = loadFixtureJson("heartbeat.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-003", envelope.messageId)
        assertTrue(envelope.hasHeartbeat())
        assertEquals(1729440010000L, envelope.heartbeat.sentAtUnixMs)
    }

    @Test
    fun testDecodeClipboardUpdateFixture() {
        val json = loadFixtureJson("clipboard_update.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-004", envelope.messageId)
        assertTrue(envelope.hasClipboardUpdate())
        val clip = envelope.clipboardUpdate
        assertEquals("clip-evt-100", clip.eventId)
        assertEquals("dev-mac-12345", clip.originDeviceId)
        assertEquals("text/plain", clip.contentType)
        assertEquals("Hello from macOS clipboard!", clip.text)
    }

    @Test
    fun testDecodeNotificationEventFixture() {
        val json = loadFixtureJson("notification_event.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-005", envelope.messageId)
        assertTrue(envelope.hasNotificationEvent())
        val notif = envelope.notificationEvent
        assertEquals("Alice", notif.title)
        assertEquals("Are we still on for coffee?", notif.body)
        assertEquals(2, notif.actionsCount)
        assertEquals("reply", notif.getActions(0).actionId)
        assertTrue(notif.getActions(0).requiresTextInput)
    }

    @Test
    fun testDecodeShareTextFixture() {
        val json = loadFixtureJson("share_text.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-006", envelope.messageId)
        assertTrue(envelope.hasShareText())
        val share = envelope.shareText
        assertEquals("text/uri-list", share.contentType)
        assertEquals("https://github.com/rajanraj2/bridge", share.content)
    }

    @Test
    fun testDecodeFileTransferRequestFixture() {
        val json = loadFixtureJson("file_transfer_request.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-007", envelope.messageId)
        assertTrue(envelope.hasFileRequest())
        val req = envelope.fileRequest
        assertEquals("photo_vacation.jpg", req.fileName)
        assertEquals(2048000L, req.totalSize)
        assertEquals("image/jpeg", req.mediaType)
    }

    @Test
    fun testDecodeErrorFixture() {
        val json = loadFixtureJson("error.json")
        val envelope = parseEnvelope(json)

        assertEquals("msg-008", envelope.messageId)
        assertTrue(envelope.hasError())
        val err = envelope.error
        assertEquals("TRANSFER_REJECTED", err.code.name)
        assertEquals("User declined incoming transfer", err.safeMessage)
        assertEquals("xfer-7001", err.correlationId)
        assertFalse(err.retryable)
    }
}
