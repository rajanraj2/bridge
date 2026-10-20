import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeSecurity
@testable import BridgeClipboard

private final class TestPasteboardAdapter: PasteboardAdapter, @unchecked Sendable {
    var storedText: String?

    func getPasteboardString() -> String? {
        return storedText
    }

    func setPasteboardString(_ text: String) {
        self.storedText = text
    }
}

final class ClipboardServiceTests: XCTestCase {

    func testLocalCopyCreatesEnvelope() {
        let service = ClipboardService()
        let env = service.handleLocalCopy(
            text: "Hello from Mac",
            localDeviceId: "mac-001",
            sessionId: "sess-1"
        )

        XCTAssertNotNil(env)
        XCTAssertEqual(env?.senderDeviceID, "mac-001")
        XCTAssertEqual(env?.sessionID, "sess-1")

        guard case .clipboardUpdate(let update) = env?.payload else {
            XCTFail("Expected clipboardUpdate payload")
            return
        }
        XCTAssertEqual(update.text, "Hello from Mac")
        XCTAssertEqual(update.originDeviceID, "mac-001")
        XCTAssertEqual(update.contentType, "text/plain")
        XCTAssertEqual(service.syncedCount, 1)
    }

    func testRemoteUpdateAppliesToPasteboard() {
        let service = ClipboardService()
        let adapter = TestPasteboardAdapter()
        service.adapter = adapter

        var env = Bridge_Envelope()
        env.messageID = "msg-1"
        env.senderDeviceID = "android-001"
        var update = Bridge_ClipboardUpdate()
        update.eventID = "evt-1"
        update.originDeviceID = "android-001"
        update.text = "Copied on Android Phone"
        update.contentSha256 = IdentityManager.sha256(data: "Copied on Android Phone".data(using: .utf8)!)
        env.clipboardUpdate = update

        let result = service.handleRemoteUpdate(envelope: env, localDeviceId: "mac-001")
        guard case .success(let text) = result else {
            XCTFail("Expected successful remote update")
            return
        }
        XCTAssertEqual(text, "Copied on Android Phone")
        XCTAssertEqual(adapter.storedText, "Copied on Android Phone")
    }

    func testEchoLoopOriginIsSelfRejection() {
        let service = ClipboardService()
        var env = Bridge_Envelope()
        env.messageID = "msg-echo"
        var update = Bridge_ClipboardUpdate()
        update.eventID = "evt-echo-1"
        update.originDeviceID = "mac-001" // Origin is local device!
        update.text = "Local text"
        update.contentSha256 = IdentityManager.sha256(data: "Local text".data(using: .utf8)!)
        env.clipboardUpdate = update

        let result = service.handleRemoteUpdate(envelope: env, localDeviceId: "mac-001")
        guard case .failure(let reason) = result else {
            XCTFail("Expected failure due to origin is self")
            return
        }
        XCTAssertEqual(reason, .originIsSelf)
        XCTAssertEqual(service.echoBlockedCount, 1)
    }

    func testDuplicateEventRejection() {
        let service = ClipboardService()
        var env = Bridge_Envelope()
        env.messageID = "msg-dup"
        var update = Bridge_ClipboardUpdate()
        update.eventID = "evt-dup-1"
        update.originDeviceID = "android-001"
        update.text = "Text"
        update.contentSha256 = IdentityManager.sha256(data: "Text".data(using: .utf8)!)
        env.clipboardUpdate = update

        _ = service.handleRemoteUpdate(envelope: env, localDeviceId: "mac-001")
        let secondResult = service.handleRemoteUpdate(envelope: env, localDeviceId: "mac-001")
        guard case .failure(let reason) = secondResult else {
            XCTFail("Expected failure due to duplicate event")
            return
        }
        XCTAssertEqual(reason, .eventAlreadyProcessed)
    }

    func testContentHashEchoSuppression() {
        let service = ClipboardService()
        // 1. Local copy records hash
        let env1 = service.handleLocalCopy(text: "Unique Secret String", localDeviceId: "mac-001", sessionId: "s1")
        XCTAssertNotNil(env1)

        // 2. Immediate second local copy of exact same content is suppressed to prevent echo
        let env2 = service.handleLocalCopy(text: "Unique Secret String", localDeviceId: "mac-001", sessionId: "s1")
        XCTAssertNil(env2)
        XCTAssertEqual(service.echoBlockedCount, 1)
    }

    func testOversizedClipboardRejected() {
        var policy = ClipboardPolicy()
        policy.maxTextLength = 100
        let service = ClipboardService(policy: policy)

        let longText = String(repeating: "A", count: 200)
        let localEnv = service.handleLocalCopy(text: longText, localDeviceId: "mac-001", sessionId: "s1")
        XCTAssertNil(localEnv)

        var env = Bridge_Envelope()
        var update = Bridge_ClipboardUpdate()
        update.eventID = "e1"
        update.originDeviceID = "android-001"
        update.text = longText
        env.clipboardUpdate = update

        let remoteResult = service.handleRemoteUpdate(envelope: env, localDeviceId: "mac-001")
        guard case .failure(let reason) = remoteResult else {
            XCTFail("Expected textExceedsLimit failure")
            return
        }
        XCTAssertEqual(reason, .textExceedsLimit)
    }
}
