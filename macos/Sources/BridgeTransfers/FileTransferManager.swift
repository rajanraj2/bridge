import Foundation
import BridgeDomain
import BridgeProtocol
import BridgeSecurity
import SwiftProtobuf

public enum TransferStatus: String, Codable, Sendable {
    case requesting
    case waitingApproval
    case streaming
    case verifying
    case completed
    case cancelled
    case failed
}

public enum TransferError: Error, Equatable {
    case transferNotFound
    case transferRejected(String)
    case offsetMismatch(expected: UInt64, actual: UInt64)
    case checksumMismatch
    case sizeMismatch(expected: UInt64, actual: UInt64)
    case ioError(String)
    case cancelled(String)
}

public struct TransferProgress: Sendable {
    public let transferId: String
    public let bytesTransferred: UInt64
    public let totalBytes: UInt64
    public let status: TransferStatus

    public var fractionCompleted: Double {
        guard totalBytes > 0 else { return 0 }
        return Double(bytesTransferred) / Double(totalBytes)
    }
}

public final class FileTransferManager: @unchecked Sendable {
    public static let defaultChunkSize: UInt32 = 262_144 // 256 KiB
    public static let maxFileSize: UInt64 = 10_737_418_240 // 10 GiB

    private let lock = NSLock()
    private let destinationDirectory: URL
    private let temporaryDirectory: URL

    // State tracking for active inbound and outbound transfers
    public struct ActiveInboundTransfer {
        public let transferId: String
        public let fileName: String
        public let totalSize: UInt64
        public let expectedSha256: Data
        public let temporaryURL: URL
        public var bytesReceived: UInt64
        public var fileHandle: FileHandle?
        public var status: TransferStatus
    }

    public struct ActiveOutboundTransfer {
        public let transferId: String
        public let sourceURL: URL
        public let totalSize: UInt64
        public let expectedSha256: Data
        public var bytesSent: UInt64
        public var chunkSize: UInt32
        public var status: TransferStatus
        public var fileHandle: FileHandle?
    }

    private var activeInbound: [String: ActiveInboundTransfer] = [:]
    private var activeOutbound: [String: ActiveOutboundTransfer] = [:]

    public init(
        destinationDirectory: URL = FileManager.default.temporaryDirectory,
        temporaryDirectory: URL = FileManager.default.temporaryDirectory.appendingPathComponent("bridge_transfers_tmp")
    ) {
        self.destinationDirectory = destinationDirectory
        self.temporaryDirectory = temporaryDirectory
        try? FileManager.default.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Sender Side

    /// Initiates an outbound file transfer.
    public func initiateTransfer(
        sourceURL: URL,
        senderDeviceId: String,
        sessionId: String,
        preferredChunkSize: UInt32 = defaultChunkSize
    ) throws -> (requestEnvelope: Bridge_Envelope, transferId: String) {
        lock.lock()
        defer { lock.unlock() }

        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw TransferError.ioError("Source file not found at \(sourceURL.path)")
        }

        let attributes = try FileManager.default.attributesOfItem(atPath: sourceURL.path)
        let fileSize = (attributes[.size] as? NSNumber)?.uint64Value ?? 0

        guard fileSize <= Self.maxFileSize else {
            throw TransferError.ioError("File exceeds maximum size of \(Self.maxFileSize) bytes")
        }

        let fileData = try Data(contentsOf: sourceURL, options: .mappedIfSafe)
        let sha256 = IdentityManager.sha256(data: fileData)

        let transferId = "xfer-" + UUID().uuidString.lowercased()
        let handle = try FileHandle(forReadingFrom: sourceURL)

        activeOutbound[transferId] = ActiveOutboundTransfer(
            transferId: transferId,
            sourceURL: sourceURL,
            totalSize: fileSize,
            expectedSha256: sha256,
            bytesSent: 0,
            chunkSize: preferredChunkSize,
            status: .requesting,
            fileHandle: handle
        )

        var req = Bridge_FileTransferRequest()
        req.transferID = transferId
        req.fileName = SafeFilename.sanitize(sourceURL.lastPathComponent)
        req.totalSize = fileSize
        req.sha256 = sha256
        req.preferredChunkSize = preferredChunkSize

        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = senderDeviceId
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0
        env.fileRequest = req

        return (env, transferId)
    }

    /// Handles recipient acceptance response and updates agreed chunk size.
    public func handleTransferAccept(_ envelope: Bridge_Envelope) throws {
        lock.lock()
        defer { lock.unlock() }

        guard case .fileAccept(let accept) = envelope.payload else { return }
        guard var outbound = activeOutbound[accept.transferID] else {
            throw TransferError.transferNotFound
        }

        if !accept.accepted {
            outbound.status = .cancelled
            outbound.fileHandle?.closeFile()
            activeOutbound.removeValue(forKey: accept.transferID)
            throw TransferError.transferRejected(accept.rejectionReason)
        }

        outbound.chunkSize = accept.chunkSize > 0 ? accept.chunkSize : Self.defaultChunkSize
        outbound.status = .streaming
        activeOutbound[accept.transferID] = outbound
    }

    /// Reads and creates the next chunk envelope for the outbound transfer.
    public func createNextChunkEnvelope(
        transferId: String,
        senderDeviceId: String,
        sessionId: String
    ) throws -> Bridge_Envelope? {
        lock.lock()
        defer { lock.unlock() }

        guard var outbound = activeOutbound[transferId] else {
            return nil
        }
        guard outbound.status != .completed else {
            return nil
        }
        guard let handle = outbound.fileHandle else {
            return nil
        }

        if outbound.bytesSent >= outbound.totalSize && outbound.totalSize > 0 {
            return nil
        }

        let chunkData = handle.readData(ofLength: Int(outbound.chunkSize))
        let offset = outbound.bytesSent
        outbound.bytesSent += UInt64(chunkData.count)
        let isLast = outbound.bytesSent >= outbound.totalSize

        activeOutbound[transferId] = outbound

        var chunk = Bridge_FileChunk()
        chunk.transferID = transferId
        chunk.offset = offset
        chunk.data = chunkData
        chunk.isLast = isLast

        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = senderDeviceId
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0
        env.fileChunk = chunk

        if isLast {
            handle.closeFile()
            outbound.status = .completed
            activeOutbound.removeValue(forKey: transferId)
        }

        return env
    }

    // MARK: - Receiver Side

    /// Handles an incoming transfer request and creates an acceptance response.
    public func handleIncomingRequest(
        _ envelope: Bridge_Envelope,
        senderDeviceId: String,
        sessionId: String,
        autoAccept: BooleanLiteralType = true
    ) throws -> Bridge_Envelope {
        lock.lock()
        defer { lock.unlock() }

        guard case .fileRequest(let req) = envelope.payload else {
            throw TransferError.transferNotFound
        }

        let transferId = req.transferID
        let safeName = SafeFilename.sanitize(req.fileName)
        let tempFile = temporaryDirectory.appendingPathComponent("\(transferId)_\(safeName).tmp")

        // Allocate empty temporary file
        _ = FileManager.default.createFile(atPath: tempFile.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tempFile)

        activeInbound[transferId] = ActiveInboundTransfer(
            transferId: transferId,
            fileName: safeName,
            totalSize: req.totalSize,
            expectedSha256: req.sha256,
            temporaryURL: tempFile,
            bytesReceived: 0,
            fileHandle: handle,
            status: autoAccept ? .streaming : .waitingApproval
        )

        var accept = Bridge_FileTransferAccept()
        accept.transferID = transferId
        accept.accepted = autoAccept
        accept.chunkSize = req.preferredChunkSize > 0 ? req.preferredChunkSize : Self.defaultChunkSize

        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = senderDeviceId
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0
        env.fileAccept = accept

        return env
    }

    /// Handles an incoming file chunk, verifies offsets, and upon last chunk commits file atomically.
    /// Returns (progress, completedDestinationURLIfFinished)
    public func handleIncomingChunk(
        _ envelope: Bridge_Envelope
    ) throws -> (progress: TransferProgress, finalURL: URL?) {
        lock.lock()
        defer { lock.unlock() }

        guard case .fileChunk(let chunk) = envelope.payload else {
            throw TransferError.transferNotFound
        }

        guard var inbound = activeInbound[chunk.transferID] else {
            throw TransferError.transferNotFound
        }
        guard let handle = inbound.fileHandle else {
            throw TransferError.ioError("File handle closed")
        }

        // Validate offset
        guard chunk.offset == inbound.bytesReceived else {
            cleanupInbound(transferId: chunk.transferID)
            throw TransferError.offsetMismatch(expected: inbound.bytesReceived, actual: chunk.offset)
        }

        handle.write(chunk.data)
        inbound.bytesReceived += UInt64(chunk.data.count)
        activeInbound[chunk.transferID] = inbound

        if chunk.isLast {
            handle.closeFile()
            inbound.fileHandle = nil

            // Verify size
            guard inbound.bytesReceived == inbound.totalSize else {
                cleanupInbound(transferId: chunk.transferID)
                throw TransferError.sizeMismatch(expected: inbound.totalSize, actual: inbound.bytesReceived)
            }

            // Verify SHA-256
            let completeData = try Data(contentsOf: inbound.temporaryURL, options: .mappedIfSafe)
            let actualSha = IdentityManager.sha256(data: completeData)

            guard actualSha == inbound.expectedSha256 else {
                cleanupInbound(transferId: chunk.transferID)
                throw TransferError.checksumMismatch
            }

            // Atomic commit to destination directory with collision resolution
            let finalURL = SafeFilename.uniqueDestinationURL(in: destinationDirectory, filename: inbound.fileName)
            try FileManager.default.moveItem(at: inbound.temporaryURL, to: finalURL)

            inbound.status = .completed
            activeInbound.removeValue(forKey: chunk.transferID)

            let progress = TransferProgress(
                transferId: chunk.transferID,
                bytesTransferred: inbound.bytesReceived,
                totalBytes: inbound.totalSize,
                status: .completed
            )
            return (progress, finalURL)
        }

        let progress = TransferProgress(
            transferId: chunk.transferID,
            bytesTransferred: inbound.bytesReceived,
            totalBytes: inbound.totalSize,
            status: .streaming
        )
        return (progress, nil)
    }

    /// Cancels an in-progress transfer and cleans up temporary files immediately.
    public func cancelTransfer(transferId: String, reason: String = "User cancelled") {
        lock.lock()
        defer { lock.unlock() }

        cleanupInbound(transferId: transferId)
        if var outbound = activeOutbound.removeValue(forKey: transferId) {
            outbound.status = .cancelled
            outbound.fileHandle?.closeFile()
        }
    }

    private func cleanupInbound(transferId: String) {
        guard let inbound = activeInbound.removeValue(forKey: transferId) else { return }
        inbound.fileHandle?.closeFile()
        try? FileManager.default.removeItem(at: inbound.temporaryURL)
    }
}
