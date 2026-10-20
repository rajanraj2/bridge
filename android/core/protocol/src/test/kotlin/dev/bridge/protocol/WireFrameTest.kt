package dev.bridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WireFrameTest {

    @Test
    fun testEncodeDecodeRoundTrip() {
        val original = "Hello from Android Bridge Protocol".toByteArray(Charsets.UTF_8)
        val encoded = WireFrame.encode(original)

        assertEquals(WireFrame.HEADER_SIZE + original.size, encoded.size)

        val inputStream = ByteArrayInputStream(encoded)
        val decoded = WireFrame.readFrame(inputStream)

        assertArrayEquals(original, decoded)
    }

    @Test
    fun testOversizedPayloadThrowsException() {
        val oversized = ByteArray(WireFrame.MAX_CONTROL_FRAME_SIZE + 1)
        try {
            WireFrame.encode(oversized)
            fail("Expected MessageTooLarge exception")
        } catch (e: FramingException.MessageTooLarge) {
            // Success
        }
    }

    @Test
    fun testTruncatedStreamThrowsException() {
        val incompleteHeader = byteArrayOf(0x00, 0x00) // only 2 bytes instead of 4
        val inputStream = ByteArrayInputStream(incompleteHeader)
        try {
            WireFrame.readFrame(inputStream)
            fail("Expected TruncatedStream exception")
        } catch (e: FramingException.TruncatedStream) {
            // Success
        }
    }

    @Test
    fun testMultipleSequentialFrames() {
        val stream = ByteArrayOutputStream()
        val msg1 = "First frame".toByteArray()
        val msg2 = "Second frame".toByteArray()

        WireFrame.writeFrame(stream, msg1)
        WireFrame.writeFrame(stream, msg2)

        val inputStream = ByteArrayInputStream(stream.toByteArray())
        val read1 = WireFrame.readFrame(inputStream)
        val read2 = WireFrame.readFrame(inputStream)

        assertArrayEquals(msg1, read1)
        assertArrayEquals(msg2, read2)
    }
}
