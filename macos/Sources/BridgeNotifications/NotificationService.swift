import Foundation
import BridgeDomain
import BridgeProtocol
import SwiftProtobuf

public struct NotificationFilterPolicy: Sendable {
    public var enabled: Bool
    public var allowedPackages: Set<String>
    public var blockedPackages: Set<String>
    public var suppressSensitive: Bool

    public init(
        enabled: Bool = true,
        allowedPackages: Set<String> = [],
        blockedPackages: Set<String> = [],
        suppressSensitive: Bool = true
    ) {
        self.enabled = enabled
        self.allowedPackages = allowedPackages
        self.blockedPackages = blockedPackages
        self.suppressSensitive = suppressSensitive
    }

    public func shouldAllow(packageId: String, isSensitive: Bool) -> Bool {
        guard enabled else { return false }
        if suppressSensitive && isSensitive {
            return false
        }
        if blockedPackages.contains(packageId) {
            return false
        }
        if !allowedPackages.isEmpty && !allowedPackages.contains(packageId) {
            return false
        }
        return true
    }
}

public final class NotificationDeduplicator: @unchecked Sendable {
    private let lock = NSLock()
    private var seenEventIds: [String: Date] = [:]
    private let ttl: TimeInterval
    private let maxEntries: Int

    public init(ttl: TimeInterval = 600, maxEntries: Int = 1000) {
        self.ttl = ttl
        self.maxEntries = maxEntries
    }

    /// Returns true if the eventId was not seen before and is now recorded.
    /// Returns false if the event is a duplicate.
    public func recordIfNotSeen(eventId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        cleanupExpired()

        if seenEventIds[eventId] != nil {
            return false
        }

        if seenEventIds.count >= maxEntries {
            // Drop oldest
            if let oldest = seenEventIds.min(by: { $0.value < $1.value })?.key {
                seenEventIds.removeValue(forKey: oldest)
            }
        }

        seenEventIds[eventId] = Date()
        return true
    }

    public func isDuplicate(eventId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        cleanupExpired()
        return seenEventIds[eventId] != nil
    }

    private func cleanupExpired() {
        let now = Date()
        seenEventIds = seenEventIds.filter { now.timeIntervalSince($0.value) < ttl }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        seenEventIds.removeAll()
    }
}

public protocol NotificationDisplayDelegate: AnyObject, Sendable {
    func displayNotification(
        eventId: String,
        sourceApp: String,
        title: String,
        body: String,
        actions: [Bridge_NotificationActionDescriptor]
    )
}

public final class NotificationService: @unchecked Sendable {
    private let lock = NSLock()
    public var filterPolicy: NotificationFilterPolicy
    private let deduplicator: NotificationDeduplicator
    public weak var displayDelegate: NotificationDisplayDelegate?

    private(set) public var receivedNotificationCount: Int = 0
    private(set) public var filteredNotificationCount: Int = 0
    private(set) public var duplicateNotificationCount: Int = 0

    public init(
        filterPolicy: NotificationFilterPolicy = NotificationFilterPolicy(),
        deduplicator: NotificationDeduplicator = NotificationDeduplicator()
    ) {
        self.filterPolicy = filterPolicy
        self.deduplicator = deduplicator
    }

    /// Ingests and processes an incoming notification event envelope.
    /// Returns true if displayed, false if filtered or duplicate.
    @discardableResult
    public func handleIncomingNotification(_ envelope: Bridge_Envelope) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        guard case .notificationEvent(let event) = envelope.payload else {
            return false
        }

        receivedNotificationCount += 1

        // Check deduplication
        guard deduplicator.recordIfNotSeen(eventId: event.eventID) else {
            duplicateNotificationCount += 1
            return false
        }

        // Check filter policy
        guard filterPolicy.shouldAllow(packageId: event.sourcePackageOrBundleID, isSensitive: event.isSensitive) else {
            filteredNotificationCount += 1
            return false
        }

        displayDelegate?.displayNotification(
            eventId: event.eventID,
            sourceApp: event.sourcePackageOrBundleID,
            title: event.title,
            body: event.body,
            actions: event.actions
        )

        return true
    }

    /// Creates an action response envelope (e.g. quick reply).
    public func createActionEnvelope(
        sessionId: String,
        senderDeviceId: String,
        targetEventId: String,
        actionId: String,
        replyText: String?
    ) -> Bridge_Envelope {
        var env = Bridge_Envelope()
        env.messageID = UUID().uuidString.lowercased()
        env.sessionID = sessionId
        env.senderDeviceID = senderDeviceId
        env.createdAtUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        env.protocolMajor = 1
        env.protocolMinor = 0

        var action = Bridge_NotificationAction()
        action.eventID = targetEventId
        action.actionID = actionId
        if let reply = replyText {
            action.textInput = reply
        }

        env.notificationAction = action
        return env
    }
}
