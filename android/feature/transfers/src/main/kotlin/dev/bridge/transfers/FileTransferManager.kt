package dev.bridge.transfers

import com.google.protobuf.ByteString
import dev.bridge.protocol.v1.Envelope
import dev.bridge.protocol.v1.FileChunk
import dev.bridge.protocol.v1.FileTransferAccept
import dev.bridge.protocol.v1.FileTransferRequest
import dev.bridge.security.IdentityManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

enum class TransferStatus {
    REQUESTING,
    WAITING_APPROVAL,
    STREAMING,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED
}

sealed class TransferException(message: String) : Exception(message) {
    class NotFound : TransferException("Transfer not found")
    class Rejected(reason: String) : TransferException("Transfer rejected: $reason")
    class OffsetMismatch(expected: Long, actual: Long) : TransferException("Offset mismatch: expected $expected, got $actual")
    class ChecksumMismatch : TransferException("SHA-256 integrity verification failed")
    class SizeMismatch(expected: Long, actual: Long) : TransferException("Size mismatch: expected $expected, got $actual")
    class Cancelled(reason: String) : TransferException("Transfer cancelled: $reason")
}

data class TransferProgress(
    val transferId: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: TransferStatus
) {
    val fractionCompleted: Double
        get() = if (totalBytes > 0) bytesTransferred.toDouble() / totalBytes else 0.0
}

class FileTransferManager(
    private val destinationDirectory: File = File(System.getProperty("java.io.tmpdir"), "bridge_downloads"),
    private val temporaryDirectory: File = File(System.getProperty("java.io.tmpdir"), "bridge_transfers_tmp")
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 262_144 // 256 KiB
        const val MAX_FILE_SIZE = 10_737_418_240L // 10 GiB
    }

    init {
        destinationDirectory.mkdirs()
        temporaryDirectory.mkdirs()
    }

    private data class ActiveInbound(
        val transferId: String,
        val fileName: String,
        val totalSize: Long,
        val expectedSha256: ByteArray,
        val tempFile: File,
        var bytesReceived: Long,
        var outputStream: FileOutputStream?,
        var status: TransferStatus
    )

    private data class ActiveOutbound(
        val transferId: String,
        val sourceFile: File,
        val totalSize: Long,
        val expectedSha256: ByteArray,
        var bytesSent: Long,
        var chunkSize: Int,
        var status: TransferStatus,
        var inputStream: FileInputStream?
    )

    private val activeInbound = mutableMapOf<String, ActiveInbound>()
    private val activeOutbound = mutableMapOf<String, ActiveOutbound>()

    // SENDER PIPELINE
    @Synchronized
    fun initiateTransfer(
        sourceFile: File,
        senderDeviceId: String,
        sessionId: String,
        preferredChunkSize: Int = DEFAULT_CHUNK_SIZE
    ): Pair<Envelope, String> {
        if (!sourceFile.exists()) {
            throw IllegalArgumentException("Source file does not exist: ${sourceFile.path}")
        }
        val fileSize = sourceFile.length()
        if (fileSize > MAX_FILE_SIZE) {
            throw IllegalArgumentException("File size $fileSize exceeds maximum limit")
        }

        val sha256 = IdentityManager.sha256(sourceFile.readBytes())
        val transferId = "xfer-" + UUID.randomUUID().toString().lowercase()

        val outbound = ActiveOutbound(
            transferId = transferId,
            sourceFile = sourceFile,
            totalSize = fileSize,
            expectedSha256 = sha256,
            bytesSent = 0,
            chunkSize = preferredChunkSize,
            status = TransferStatus.REQUESTING,
            inputStream = FileInputStream(sourceFile)
        )
        activeOutbound[transferId] = outbound

        val req = FileTransferRequest.newBuilder()
            .setTransferId(transferId)
            .setFileName(SafeFilename.sanitize(sourceFile.name))
            .setTotalSize(fileSize)
            .setSha256(ByteString.copyFrom(sha256))
            .setPreferredChunkSize(preferredChunkSize)
            .build()

        val env = Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(senderDeviceId)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(1)
            .setProtocolMinor(0)
            .setFileRequest(req)
            .build()

        return Pair(env, transferId)
    }

    @Synchronized
    fun handleTransferAccept(envelope: Envelope) {
        if (!envelope.hasFileAccept()) return
        val accept = envelope.fileAccept
        val outbound = activeOutbound[accept.transferId] ?: throw TransferException.NotFound()

        if (!accept.accepted) {
            outbound.status = TransferStatus.CANCELLED
            outbound.inputStream?.close()
            activeOutbound.remove(accept.transferId)
            throw TransferException.Rejected(accept.rejectionReason)
        }

        outbound.chunkSize = if (accept.chunkSize > 0) accept.chunkSize else DEFAULT_CHUNK_SIZE
        outbound.status = TransferStatus.STREAMING
    }

    @Synchronized
    fun createNextChunkEnvelope(
        transferId: String,
        senderDeviceId: String,
        sessionId: String
    ): Envelope? {
        val outbound = activeOutbound[transferId] ?: return null
        if (outbound.status == TransferStatus.COMPLETED) return null
        val inputStream = outbound.inputStream ?: return null

        if (outbound.bytesSent >= outbound.totalSize && outbound.totalSize > 0) {
            return null
        }

        val buffer = ByteArray(outbound.chunkSize)
        val read = inputStream.read(buffer)
        val actualBytes = if (read > 0) buffer.copyOf(read) else ByteArray(0)

        val offset = outbound.bytesSent
        outbound.bytesSent += actualBytes.size
        val isLast = outbound.bytesSent >= outbound.totalSize

        val chunk = FileChunk.newBuilder()
            .setTransferId(transferId)
            .setOffset(offset)
            .setData(ByteString.copyFrom(actualBytes))
            .setIsLast(isLast)
            .build()

        val env = Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(senderDeviceId)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(1)
            .setProtocolMinor(0)
            .setFileChunk(chunk)
            .build()

        if (isLast) {
            inputStream.close()
            outbound.status = TransferStatus.COMPLETED
            activeOutbound.remove(transferId)
        }

        return env
    }

    // RECEIVER PIPELINE
    @Synchronized
    fun handleIncomingRequest(
        envelope: Envelope,
        senderDeviceId: String,
        sessionId: String,
        autoAccept: Boolean = true
    ): Envelope {
        if (!envelope.hasFileRequest()) throw TransferException.NotFound()
        val req = envelope.fileRequest
        val transferId = req.transferId
        val safeName = SafeFilename.sanitize(req.fileName)
        val tempFile = File(temporaryDirectory, "${transferId}_${safeName}.tmp")

        tempFile.createNewFile()
        val outputStream = FileOutputStream(tempFile)

        activeInbound[transferId] = ActiveInbound(
            transferId = transferId,
            fileName = safeName,
            totalSize = req.totalSize,
            expectedSha256 = req.sha256.toByteArray(),
            tempFile = tempFile,
            bytesReceived = 0,
            outputStream = outputStream,
            status = if (autoAccept) TransferStatus.STREAMING else TransferStatus.WAITING_APPROVAL
        )

        val accept = FileTransferAccept.newBuilder()
            .setTransferId(transferId)
            .setAccepted(autoAccept)
            .setChunkSize(if (req.preferredChunkSize > 0) req.preferredChunkSize else DEFAULT_CHUNK_SIZE)
            .build()

        return Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(senderDeviceId)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(1)
            .setProtocolMinor(0)
            .setFileAccept(accept)
            .build()
    }

    @Synchronized
    fun handleIncomingChunk(envelope: Envelope): Pair<TransferProgress, File?> {
        if (!envelope.hasFileChunk()) throw TransferException.NotFound()
        val chunk = envelope.fileChunk
        val inbound = activeInbound[chunk.transferId] ?: throw TransferException.NotFound()
        val outputStream = inbound.outputStream ?: throw IllegalStateException("Stream closed")

        if (chunk.offset != inbound.bytesReceived) {
            cleanupInbound(chunk.transferId)
            throw TransferException.OffsetMismatch(inbound.bytesReceived, chunk.offset)
        }

        val data = chunk.data.toByteArray()
        outputStream.write(data)
        outputStream.flush()
        inbound.bytesReceived += data.size

        if (chunk.isLast) {
            outputStream.close()
            inbound.outputStream = null

            if (inbound.bytesReceived != inbound.totalSize) {
                cleanupInbound(chunk.transferId)
                throw TransferException.SizeMismatch(inbound.totalSize, inbound.bytesReceived)
            }

            val actualSha256 = IdentityManager.sha256(inbound.tempFile.readBytes())
            if (!actualSha256.contentEquals(inbound.expectedSha256)) {
                cleanupInbound(chunk.transferId)
                throw TransferException.ChecksumMismatch()
            }

            val finalDestination = SafeFilename.uniqueDestination(destinationDirectory, inbound.fileName)
            inbound.tempFile.renameTo(finalDestination)

            inbound.status = TransferStatus.COMPLETED
            activeInbound.remove(chunk.transferId)

            val progress = TransferProgress(chunk.transferId, inbound.bytesReceived, inbound.totalSize, TransferStatus.COMPLETED)
            return Pair(progress, finalDestination)
        }

        val progress = TransferProgress(chunk.transferId, inbound.bytesReceived, inbound.totalSize, TransferStatus.STREAMING)
        return Pair(progress, null)
    }

    @Synchronized
    fun cancelTransfer(transferId: String, reason: String = "User cancelled") {
        cleanupInbound(transferId)
        val outbound = activeOutbound.remove(transferId)
        if (outbound != null) {
            outbound.status = TransferStatus.CANCELLED
            outbound.inputStream?.close()
        }
    }

    private fun cleanupInbound(transferId: String) {
        val inbound = activeInbound.remove(transferId) ?: return
        inbound.outputStream?.close()
        if (inbound.tempFile.exists()) {
            inbound.tempFile.delete()
        }
    }
}
