package dev.bridge.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class FramingException(message: String) : Exception(message) {
    class MessageTooLarge(actual: Int, max: Int) :
        FramingException("Message length $actual exceeds maximum allowed $max bytes")
    class TruncatedStream : FramingException("Stream ended before expected bytes could be read")
}

object WireFrame {
    const val MAX_CONTROL_FRAME_SIZE = 1_048_576 // 1 MiB
    const val HEADER_SIZE = 4

    /**
     * Encodes a payload byte array with a 4-byte big-endian length prefix.
     */
    fun encode(payload: ByteArray): ByteArray {
        if (payload.size > MAX_CONTROL_FRAME_SIZE) {
            throw FramingException.MessageTooLarge(payload.size, MAX_CONTROL_FRAME_SIZE)
        }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Writes length-delimited payload to output stream.
     */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val framed = encode(payload)
        output.write(framed)
        output.flush()
    }

    /**
     * Reads a single length-delimited frame from an input stream.
     */
    fun readFrame(input: InputStream): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        var bytesRead = 0
        while (bytesRead < HEADER_SIZE) {
            val count = input.read(header, bytesRead, HEADER_SIZE - bytesRead)
            if (count == -1) {
                if (bytesRead == 0) throw FramingException.TruncatedStream()
                throw FramingException.TruncatedStream()
            }
            bytesRead += count
        }

        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (length < 0 || length > MAX_CONTROL_FRAME_SIZE) {
            throw FramingException.MessageTooLarge(length, MAX_CONTROL_FRAME_SIZE)
        }

        val payload = ByteArray(length)
        var payloadRead = 0
        while (payloadRead < length) {
            val count = input.read(payload, payloadRead, length - payloadRead)
            if (count == -1) {
                throw FramingException.TruncatedStream()
            }
            payloadRead += count
        }
        return payload
    }
}
