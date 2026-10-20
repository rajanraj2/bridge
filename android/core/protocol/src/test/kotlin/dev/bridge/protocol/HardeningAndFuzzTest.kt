package dev.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class HardeningAndFuzzTest {

    @Test
    fun testWireFramingRandomStreamFuzzing() {
        val random = Random(42)

        for (i in 0 until 100) {
            val length = random.nextInt(0, 1024)
            val randomBytes = random.nextBytes(length)
            val stream = ByteArrayInputStream(randomBytes)

            try {
                // Must either return valid payload or throw FramingException
                val payload = WireFrame.readFrame(stream)
                assertNotNull(payload)
            } catch (e: FramingException) {
                // Expected rejection of malformed or truncated payload
                assertTrue(e is FramingException.MessageTooLarge || e is FramingException.TruncatedStream)
            } catch (e: Exception) {
                fail("Unexpected non-FramingException on fuzz iteration $i: ${e.message}")
            }
        }
    }

    @Test
    fun testExtremeLengthHeaders() {
        // Negative length (large integer > 1MB)
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(-1) // 0xFFFFFFFF
        val stream = ByteArrayInputStream(buffer.array())

        try {
            WireFrame.readFrame(stream)
            fail("Expected MessageTooLarge exception for negative/oversized length")
        } catch (e: FramingException.MessageTooLarge) {
            // Expected
        }

        // Zero length frame
        val zeroBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        zeroBuffer.putInt(0)
        val zeroStream = ByteArrayInputStream(zeroBuffer.array())
        val result = WireFrame.readFrame(zeroStream)
        assertEquals(0, result.size)
    }

    @Test
    fun testCorruptedInterruptedFrameStream() {
        // Header says 100 bytes, but stream only has 20 bytes
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(100)
        buffer.put(ByteArray(20) { 0x42 })
        val stream = ByteArrayInputStream(buffer.array())

        try {
            WireFrame.readFrame(stream)
            fail("Expected TruncatedStream exception")
        } catch (e: FramingException.TruncatedStream) {
            // Expected
        }
    }
}
