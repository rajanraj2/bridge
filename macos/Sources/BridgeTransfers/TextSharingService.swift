import Foundation
import BridgeDomain
import BridgeProtocol
import SwiftProtobuf

public enum TextSharingType: String, Sendable {
    case plainText = "text/plain"
    case uriList = "text/uri-list"
}

public protocol TextSharingDelegate: AnyObject, Sendable {
    func didReceiveSharedText(shareId: String, content: String, type: TextSharingType)
}

public final class TextSharingService: @unchecked Sendable {
    public static let maxTextLength: Int = 1_048_576 // 1 MiB
    public static let maxURLLength: Int = 8192

    private let lock = NSLock()
    public weak var delegate: TextSharingDelegate?

    public init() {}

    /// Creates an envelope containing shared text or URL.
    public func createShareEnvelope(
        content: String,
        isURL: Bool,
        senderDeviceId: String,
        sessionId: String
    ) throws -> Bridge_Envelope {
        lock.lock()
        defer { lock.unlock() }

        if isURL && content.count > Self.maxURLLength {
            throw NSError(domain: "TextSharing", code: 400, userInfo: [NSLocalizedDescriptionKey: "URL exceeds maximum length of \(Self.maxURLLength) characters"])
        }
        if content.count > Self.maxTextLength {
            throw NSError(domain: "TextSharing", code: 400, userInfo: [NSLocalizedDescriptionKey: "Text exceeds maximum length of \(Self.maxTextLength) characters"])
        }

        let shareId = "share-" + UUID().uuidString.lowercased()
        var share = Bridge_ShareText()
        share.shareID = shareId
        share.contentType = isURL ? TextSharingType.uriList.rawValue : TextSharingType.plainText.rawValue
        share.content = content

        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = senderDeviceId
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0
        env.shareText = share

        return env
    }

    /// Handles an incoming ShareText envelope.
    public func handleIncomingShare(_ envelope: Bridge_Envelope) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        guard case .shareText(let share) = envelope.payload else {
            return false
        }

        let type = TextSharingType(rawValue: share.contentType) ?? .plainText
        delegate?.didReceiveSharedText(shareId: share.shareID, content: share.content, type: type)
        return true
    }
}
