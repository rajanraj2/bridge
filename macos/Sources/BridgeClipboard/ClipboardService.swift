import Foundation
import BridgeDomain
import BridgeProtocol
import BridgeSecurity
import SwiftProtobuf

public enum ClipboardEchoRejectionReason: String, Error, Sendable {
    case originIsSelf
    case eventAlreadyProcessed
    case contentHashMatchesRecent
    case textExceedsLimit
    case syncDisabled
}

public struct ClipboardPolicy: Sendable {
    public var enabled: Bool
    public var maxTextLength: Int
    public var preventEchoLoops: Bool

    public init(enabled: Bool = true, maxTextLength: Int = 500_000, preventEchoLoops: Bool = true) {
        self.enabled = enabled
        self.maxTextLength = maxTextLength
        self.preventEchoLoops = preventEchoLoops
    }
}

public final class ClipboardCache: @unchecked Sendable {
    private let lock = NSLock()
    private var processedEventIds: [String: Date] = [:]
    private var recentContentHashes: [String: Date] = [:]
    private let ttl: TimeInterval
    private let maxEntries: Int

    public init(ttl: TimeInterval = 300, maxEntries: Int = 500) {
        self.ttl = ttl
        self.maxEntries = maxEntries
    }

    public func recordEvent(eventId: String, contentSha256Hex: String) {
        lock.lock()
        defer { lock.unlock() }

        cleanup()
        let now = Date()
        processedEventIds[eventId] = now
        recentContentHashes[contentSha256Hex] = now

        if processedEventIds.count > maxEntries {
            if let oldest = processedEventIds.min(by: { $0.value < $1.value })?.key {
                processedEventIds.removeValue(forKey: oldest)
            }
        }
        if recentContentHashes.count > maxEntries {
            if let oldest = recentContentHashes.min(by: { $0.value < $1.value })?.key {
                recentContentHashes.removeValue(forKey: oldest)
            }
        }
    }

    public func isEventProcessed(eventId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        cleanup()
        return processedEventIds[eventId] != nil
    }

    public func isContentRecent(contentSha256Hex: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        cleanup()
        return recentContentHashes[contentSha256Hex] != nil
    }

    private func cleanup() {
        let now = Date()
        processedEventIds = processedEventIds.filter { now.timeIntervalSince($0.value) < ttl }
        recentContentHashes = recentContentHashes.filter { now.timeIntervalSince($0.value) < ttl }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        processedEventIds.removeAll()
        recentContentHashes.removeAll()
    }
}

public struct ReceivedClipboardPayload: Sendable {
    public let text: String?
    public let imageData: Data?
    public let contentType: String
    public let fileName: String?

    public init(text: String? = nil, imageData: Data? = nil, contentType: String = "text/plain", fileName: String? = nil) {
        self.text = text
        self.imageData = imageData
        self.contentType = contentType
        self.fileName = fileName
    }
}

public protocol PasteboardAdapter: AnyObject, Sendable {
    func getPasteboardString() -> String?
    func setPasteboardString(_ text: String)
}
public final class ClipboardService: @unchecked Sendable {
    private let lock = NSLock()
    public var policy: ClipboardPolicy
    private let cache: ClipboardCache
    public weak var adapter: PasteboardAdapter?

    private(set) public var localEventCounter: UInt64 = 0
    private(set) public var syncedCount: Int = 0
    private(set) public var echoBlockedCount: Int = 0

    public init(
        policy: ClipboardPolicy = ClipboardPolicy(),
        cache: ClipboardCache = ClipboardCache()
    ) {
        self.policy = policy
        self.cache = cache
    }

    /// Called when local pasteboard text change is detected.
    /// Creates a ClipboardUpdate envelope to forward to the paired peer.
    /// Returns nil if policy forbids sync or text is unchanged.
    public func handleLocalCopy(
        text: String,
        localDeviceId: String,
        sessionId: String,
        forceSend: Bool = false
    ) -> Bridge_Envelope? {
        lock.lock()
        defer { lock.unlock() }

        guard policy.enabled else { return nil }
        guard text.count <= policy.maxTextLength else { return nil }

        let textData = text.data(using: .utf8) ?? Data()
        let shaData = IdentityManager.sha256(data: textData)
        let shaHex = shaData.map { String(format: "%02x", $0) }.joined()

        if !forceSend && policy.preventEchoLoops && cache.isContentRecent(contentSha256Hex: shaHex) {
            // Already received from peer recently; do not echo back
            echoBlockedCount += 1
            return nil
        }

        localEventCounter += 1
        let eventId = "clip-" + UUID().uuidString.lowercased()
        let originEventId = "local-\(localEventCounter)"

        cache.recordEvent(eventId: eventId, contentSha256Hex: shaHex)

        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = localDeviceId
        env.sequenceNumber = localEventCounter
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0

        var update = Bridge_ClipboardUpdate()
        update.eventID = eventId
        update.originDeviceID = localDeviceId
        update.originEventID = originEventId
        update.contentType = "text/plain"
        update.text = text
        update.contentSha256 = shaData
        update.createdAtUnixMs = env.createdAtUnixMs

        env.clipboardUpdate = update
        syncedCount += 1
        return env
    }
    /// Creates an image/file clipboard update envelope.
    public func handleLocalImageCopy(
        imageData: Data,
        contentType: String = "image/png",
        fileName: String? = nil,
        localDeviceId: String,
        sessionId: String,
        forceSend: Bool = false
    ) -> Bridge_Envelope? {
        lock.lock()
        defer { lock.unlock() }

        guard policy.enabled else { return nil }
        guard imageData.count <= 10_485_760 else { return nil }

        let shaData = IdentityManager.sha256(data: imageData)
        let shaHex = shaData.map { String(format: "%02x", $0) }.joined()

        if !forceSend && policy.preventEchoLoops && cache.isContentRecent(contentSha256Hex: shaHex) {
            echoBlockedCount += 1
            return nil
        }

        localEventCounter += 1
        let eventId = "clip-img-" + UUID().uuidString.lowercased()
        let originEventId = "local-\(localEventCounter)"

        cache.recordEvent(eventId: eventId, contentSha256Hex: shaHex)

        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = localDeviceId
        env.sequenceNumber = localEventCounter
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0

        var update = Bridge_ClipboardUpdate()
        update.eventID = eventId
        update.originDeviceID = localDeviceId
        update.originEventID = originEventId
        update.contentType = contentType
        update.imageData = imageData
        if let fn = fileName {
            update.fileName = fn
        }
        update.contentSha256 = shaData
        update.createdAtUnixMs = env.createdAtUnixMs

        env.clipboardUpdate = update
        syncedCount += 1
        return env
    }

    /// Ingests a received ClipboardUpdate envelope supporting text and image payloads.
    public func handleRemotePayload(
        envelope: Bridge_Envelope,
        localDeviceId: String
    ) -> Result<ReceivedClipboardPayload, ClipboardEchoRejectionReason> {
        lock.lock()
        defer { lock.unlock() }

        guard policy.enabled else {
            return .failure(.syncDisabled)
        }

        guard case .clipboardUpdate(let update) = envelope.payload else {
            return .failure(.eventAlreadyProcessed)
        }

        if update.originDeviceID == localDeviceId {
            echoBlockedCount += 1
            return .failure(.originIsSelf)
        }

        if cache.isEventProcessed(eventId: update.eventID) {
            echoBlockedCount += 1
            return .failure(.eventAlreadyProcessed)
        }

        if update.text.count > policy.maxTextLength {
            return .failure(.textExceedsLimit)
        }

        let shaHex = update.contentSha256.map { String(format: "%02x", $0) }.joined()
        cache.recordEvent(eventId: update.eventID, contentSha256Hex: shaHex)

        if !update.text.isEmpty {
            adapter?.setPasteboardString(update.text)
        }
        syncedCount += 1

        let payload = ReceivedClipboardPayload(
            text: update.text.isEmpty ? nil : update.text,
            imageData: update.imageData.isEmpty ? nil : update.imageData,
            contentType: update.contentType,
            fileName: update.fileName.isEmpty ? nil : update.fileName
        )
        return .success(payload)
    }

    public func handleRemoteUpdate(
        envelope: Bridge_Envelope,
        localDeviceId: String
    ) -> Result<String, ClipboardEchoRejectionReason> {
        switch handleRemotePayload(envelope: envelope, localDeviceId: localDeviceId) {
        case .success(let payload):
            return .success(payload.text ?? "[Image Received]")
        case .failure(let err):
            return .failure(err)
        }
    }
}
