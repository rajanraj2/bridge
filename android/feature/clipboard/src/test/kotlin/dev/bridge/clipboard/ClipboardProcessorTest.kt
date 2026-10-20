package dev.bridge.clipboard

import com.google.protobuf.ByteString
import dev.bridge.protocol.v1.ClipboardUpdate
import dev.bridge.protocol.v1.Envelope
import dev.bridge.security.IdentityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardProcessorTest {

    @Test
    fun testLocalCopyCreatesEnvelope() {
        val processor = ClipboardProcessor()
        val envelope = processor.handleLocalCopy(
            text = "Hello from Android",
            localDeviceId = "android-001",
            sessionId = "sess-1"
        )

        assertNotNull(envelope)
        assertEquals("android-001", envelope?.senderDeviceId)
        assertEquals("sess-1", envelope?.sessionId)
        assertTrue(envelope!!.hasClipboardUpdate())

        val update = envelope.clipboardUpdate
        assertEquals("Hello from Android", update.text)
        assertEquals("android-001", update.originDeviceId)
        assertEquals("text/plain", update.contentType)
        assertEquals(1, processor.syncedCount)
    }

    @Test
    fun testRemoteUpdateAppliesToDestination() {
        var appliedText: String? = null
        val adapter = object : ClipboardDestinationAdapter {
            override fun setClipboardText(text: String) {
                appliedText = text
            }
        }
        val processor = ClipboardProcessor(destinationAdapter = adapter)

        val text = "Copied on Mac"
        val sha = IdentityManager.sha256(text.toByteArray())

        val update = ClipboardUpdate.newBuilder()
            .setEventId("evt-remote-1")
            .setOriginDeviceId("mac-001")
            .setText(text)
            .setContentSha256(ByteString.copyFrom(sha))
            .build()

        val env = Envelope.newBuilder()
            .setMessageId("msg-1")
            .setSenderDeviceId("mac-001")
            .setClipboardUpdate(update)
            .build()

        val result = processor.handleRemoteUpdate(env, localDeviceId = "android-001")
        assertTrue(result.isSuccess)
        assertEquals("Copied on Mac", result.getOrNull())
        assertEquals("Copied on Mac", appliedText)
    }

    @Test
    fun testEchoLoopOriginIsSelfRejection() {
        val processor = ClipboardProcessor()
        val update = ClipboardUpdate.newBuilder()
            .setEventId("evt-echo-1")
            .setOriginDeviceId("android-001") // Origin is self
            .setText("Self text")
            .setContentSha256(ByteString.copyFrom(IdentityManager.sha256("Self text".toByteArray())))
            .build()

        val env = Envelope.newBuilder()
            .setMessageId("msg-echo")
            .setClipboardUpdate(update)
            .build()

        val result = processor.handleRemoteUpdate(env, localDeviceId = "android-001")
        assertTrue(result.isFailure)
        assertEquals(1, processor.echoBlockedCount)
    }

    @Test
    fun testDuplicateEventRejection() {
        val processor = ClipboardProcessor()
        val update = ClipboardUpdate.newBuilder()
            .setEventId("evt-dup-1")
            .setOriginDeviceId("mac-001")
            .setText("Some text")
            .setContentSha256(ByteString.copyFrom(IdentityManager.sha256("Some text".toByteArray())))
            .build()

        val env = Envelope.newBuilder()
            .setMessageId("msg-dup")
            .setClipboardUpdate(update)
            .build()

        val first = processor.handleRemoteUpdate(env, localDeviceId = "android-001")
        assertTrue(first.isSuccess)

        val second = processor.handleRemoteUpdate(env, localDeviceId = "android-001")
        assertTrue(second.isFailure)
    }

    @Test
    fun testContentHashEchoSuppression() {
        val processor = ClipboardProcessor()

        // 1. Local copy records hash
        val env1 = processor.handleLocalCopy("Secret Link", "android-001", "sess-1")
        assertNotNull(env1)

        // 2. Second local copy of exact same content is suppressed to prevent echo
        val env2 = processor.handleLocalCopy("Secret Link", "android-001", "sess-1")
        assertNull("Identical text must be suppressed on repeated local copy", env2)
        assertEquals(1, processor.echoBlockedCount)
    }

    @Test
    fun testOversizedClipboardRejected() {
        val policy = ClipboardPolicy(maxTextLength = 100)
        val processor = ClipboardProcessor(policy = policy)

        val longText = "A".repeat(200)
        val localEnv = processor.handleLocalCopy(longText, "android-001", "sess-1")
        assertNull(localEnv)

        val update = ClipboardUpdate.newBuilder()
            .setEventId("e1")
            .setOriginDeviceId("mac-001")
            .setText(longText)
            .build()
        val env = Envelope.newBuilder().setClipboardUpdate(update).build()

        val result = processor.handleRemoteUpdate(env, "android-001")
        assertTrue(result.isFailure)
    }
}
