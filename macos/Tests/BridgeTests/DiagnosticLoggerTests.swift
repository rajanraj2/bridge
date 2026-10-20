import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeNetwork

final class DiagnosticLoggerTests: XCTestCase {

    func testSensitiveFieldRedaction() {
        let logger = DiagnosticLogger()
        logger.log(
            category: .connectionState,
            message: "State update",
            details: [
                "route": "LOCAL_LAN",
                "clipboardText": "Sensitive user text copied",
                "notificationBody": "Your verification code is 9988",
                "privateKey": "ed25519_secret_key"
            ]
        )

        let events = logger.getEvents()
        XCTAssertEqual(events.count, 1)

        let details = events[0].details
        XCTAssertEqual(details["route"], "LOCAL_LAN")
        XCTAssertEqual(details["clipboardText"], "[REDACTED]")
        XCTAssertEqual(details["notificationBody"], "[REDACTED]")
        XCTAssertEqual(details["privateKey"], "[REDACTED]")
    }

    func testExportDiagnosticBundle() {
        let logger = DiagnosticLogger()
        logger.log(category: .heartbeat, message: "Ping", details: ["latency": "5ms"])

        let bundle = logger.exportDiagnosticBundle(appVersion: "0.1.0", osVersion: "macOS 15.0")
        XCTAssertTrue(bundle.contains("\"bridgeAppVersion\" : \"0.1.0\""))
        XCTAssertTrue(bundle.contains("\"os\" : \"macOS\""))
        XCTAssertTrue(bundle.contains("\"heartbeat\""))
    }
}
