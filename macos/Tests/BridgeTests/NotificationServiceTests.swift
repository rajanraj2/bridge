import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeNotifications

private final class TestNotificationDisplayDelegate: NotificationDisplayDelegate, @unchecked Sendable {
    var displayedEvents: [(eventId: String, sourceApp: String, title: String, body: String)] = []

    func displayNotification(
        eventId: String,
        sourceApp: String,
        title: String,
        body: String,
        actions: [Bridge_NotificationActionDescriptor]
    ) {
        displayedEvents.append((eventId, sourceApp, title, body))
    }
}

final class NotificationServiceTests: XCTestCase {

    func testNotificationPresentationAndDeduplication() {
        let service = NotificationService()
        let delegate = TestNotificationDisplayDelegate()
        service.displayDelegate = delegate

        var env = Bridge_Envelope()
        env.messageID = "msg-1"
        var notif = Bridge_NotificationEvent()
        notif.eventID = "evt-100"
        notif.sourcePackageOrBundleID = "com.google.android.apps.messaging"
        notif.title = "Alice"
        notif.body = "Hey there"
        notif.isSensitive = false
        env.notificationEvent = notif

        // First delivery: should succeed and display
        let displayed1 = service.handleIncomingNotification(env)
        XCTAssertTrue(displayed1)
        XCTAssertEqual(delegate.displayedEvents.count, 1)
        XCTAssertEqual(delegate.displayedEvents.first?.title, "Alice")

        // Duplicate delivery with same eventId: must be rejected
        let displayed2 = service.handleIncomingNotification(env)
        XCTAssertFalse(displayed2)
        XCTAssertEqual(delegate.displayedEvents.count, 1)
        XCTAssertEqual(service.duplicateNotificationCount, 1)
    }

    func testFilterBlockedPackage() {
        var policy = NotificationFilterPolicy()
        policy.blockedPackages = ["com.spam.annoying"]
        let service = NotificationService(filterPolicy: policy)
        let delegate = TestNotificationDisplayDelegate()
        service.displayDelegate = delegate

        var env = Bridge_Envelope()
        env.messageID = "msg-2"
        var notif = Bridge_NotificationEvent()
        notif.eventID = "evt-200"
        notif.sourcePackageOrBundleID = "com.spam.annoying"
        notif.title = "Spam"
        notif.body = "Sale"
        env.notificationEvent = notif

        let displayed = service.handleIncomingNotification(env)
        XCTAssertFalse(displayed)
        XCTAssertEqual(delegate.displayedEvents.count, 0)
        XCTAssertEqual(service.filteredNotificationCount, 1)
    }

    func testSensitiveContentSuppression() {
        var policy = NotificationFilterPolicy()
        policy.suppressSensitive = true
        let service = NotificationService(filterPolicy: policy)
        let delegate = TestNotificationDisplayDelegate()
        service.displayDelegate = delegate

        var env = Bridge_Envelope()
        env.messageID = "msg-3"
        var notif = Bridge_NotificationEvent()
        notif.eventID = "evt-300"
        notif.sourcePackageOrBundleID = "com.bank.app"
        notif.title = "Bank OTP"
        notif.body = "Your PIN is 4829"
        notif.isSensitive = true
        env.notificationEvent = notif

        let displayed = service.handleIncomingNotification(env)
        XCTAssertFalse(displayed)
        XCTAssertEqual(delegate.displayedEvents.count, 0)
    }

    func testCreateActionEnvelope() {
        let service = NotificationService()
        let env = service.createActionEnvelope(
            sessionId: "sess-1",
            senderDeviceId: "mac-001",
            targetEventId: "evt-100",
            actionId: "reply",
            replyText: "Got it, thanks!"
        )

        guard case .notificationAction(let action) = env.payload else {
            XCTFail("Expected notificationAction payload")
            return
        }
        XCTAssertEqual(action.eventID, "evt-100")
        XCTAssertEqual(action.actionID, "reply")
        XCTAssertEqual(action.textInput, "Got it, thanks!")
    }
}
