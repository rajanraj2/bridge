import XCTest
import Foundation
@testable import BridgeProtocol

final class ProtocolFixtureTests: XCTestCase {
    private var fixturesDirectory: URL {
        if let workspace = ProcessInfo.processInfo.environment["GITHUB_WORKSPACE"] {
            let candidate = URL(fileURLWithPath: workspace)
                .appendingPathComponent("protocol")
                .appendingPathComponent("fixtures")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }
        var dir = URL(fileURLWithPath: #filePath)
        while dir.path != "/" && dir.path != "." {
            let candidate = dir.appendingPathComponent("protocol").appendingPathComponent("fixtures")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            dir = dir.deletingLastPathComponent()
        }
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        let cwdCandidates = [
            cwd.appendingPathComponent("protocol/fixtures"),
            cwd.appendingPathComponent("../protocol/fixtures")
        ]
        for candidate in cwdCandidates {
            let standard = candidate.standardized
            if FileManager.default.fileExists(atPath: standard.path) {
                return standard
            }
        }
        return URL(fileURLWithPath: "/home/zorax/zrx/bridge/protocol/fixtures")
    }
    private func loadFixture(named name: String) throws -> Data {
        let fixtureURL = fixturesDirectory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: fixtureURL.path) else {
            throw NSError(domain: "FixtureNotFound", code: 404, userInfo: [NSFilePathErrorKey: fixtureURL.path])
        }
        return try Data(contentsOf: fixtureURL)
    }

    func testDecodeHelloFixture() throws {
        let data = try loadFixture(named: "hello.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-001")
        XCTAssertEqual(envelope.sessionID, "sess-alpha-001")
        XCTAssertEqual(envelope.senderDeviceID, "dev-mac-12345")
        XCTAssertEqual(envelope.sequenceNumber, 1)
        XCTAssertEqual(envelope.protocolMajor, 1)
        XCTAssertEqual(envelope.protocolMinor, 0)

        guard case .hello(let hello) = envelope.payload else {
            XCTFail("Expected hello payload")
            return
        }
        XCTAssertEqual(hello.deviceID, "dev-mac-12345")
        XCTAssertEqual(hello.displayName, "MacBook Pro")
        XCTAssertEqual(hello.platform, "macos")
        XCTAssertEqual(hello.supportedVersions.count, 1)
        XCTAssertEqual(hello.supportedVersions.first?.major, 1)
        XCTAssertEqual(hello.capabilities, ["clipboard", "notifications", "transfers"])
    }

    func testDecodeHelloAckFixture() throws {
        let data = try loadFixture(named: "hello_ack.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-002")
        guard case .helloAck(let ack) = envelope.payload else {
            XCTFail("Expected helloAck payload")
            return
        }
        XCTAssertEqual(ack.selectedVersion.major, 1)
        XCTAssertEqual(ack.sessionID, "sess-alpha-001")
        XCTAssertEqual(ack.acceptedCapabilities, ["clipboard", "notifications", "transfers"])
    }

    func testDecodeHeartbeatFixture() throws {
        let data = try loadFixture(named: "heartbeat.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-003")
        guard case .heartbeat(let hb) = envelope.payload else {
            XCTFail("Expected heartbeat payload")
            return
        }
        XCTAssertEqual(hb.sentAtUnixMs, 1729440010000)
    }

    func testDecodeClipboardUpdateFixture() throws {
        let data = try loadFixture(named: "clipboard_update.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-004")
        guard case .clipboardUpdate(let clip) = envelope.payload else {
            XCTFail("Expected clipboardUpdate payload")
            return
        }
        XCTAssertEqual(clip.eventID, "clip-evt-100")
        XCTAssertEqual(clip.text, "Hello from macOS clipboard!")
        XCTAssertEqual(clip.contentType, "text/plain")
    }

    func testDecodeNotificationEventFixture() throws {
        let data = try loadFixture(named: "notification_event.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-005")
        guard case .notificationEvent(let notif) = envelope.payload else {
            XCTFail("Expected notificationEvent payload")
            return
        }
        XCTAssertEqual(notif.title, "Alice")
        XCTAssertEqual(notif.body, "Are we still on for coffee?")
        XCTAssertEqual(notif.actions.count, 2)
        XCTAssertEqual(notif.actions[0].actionID, "reply")
        XCTAssertTrue(notif.actions[0].requiresTextInput)
    }

    func testDecodeShareTextFixture() throws {
        let data = try loadFixture(named: "share_text.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-006")
        guard case .shareText(let share) = envelope.payload else {
            XCTFail("Expected shareText payload")
            return
        }
        XCTAssertEqual(share.contentType, "text/uri-list")
        XCTAssertEqual(share.content, "https://github.com/rajanraj2/bridge")
    }

    func testDecodeFileTransferRequestFixture() throws {
        let data = try loadFixture(named: "file_transfer_request.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-007")
        guard case .fileRequest(let req) = envelope.payload else {
            XCTFail("Expected fileRequest payload")
            return
        }
        XCTAssertEqual(req.fileName, "photo_vacation.jpg")
        XCTAssertEqual(req.totalSize, 2048000)
        XCTAssertEqual(req.mediaType, "image/jpeg")
    }

    func testDecodeErrorFixture() throws {
        let data = try loadFixture(named: "error.json")
        let envelope = try Bridge_Envelope(jsonUTF8Data: data)

        XCTAssertEqual(envelope.messageID, "msg-008")
        guard case .error(let err) = envelope.payload else {
            XCTFail("Expected error payload")
            return
        }
        XCTAssertEqual(err.code, .transferRejected)
        XCTAssertEqual(err.safeMessage, "User declined incoming transfer")
        XCTAssertEqual(err.correlationID, "xfer-7001")
        XCTAssertFalse(err.retryable)
    }
}
