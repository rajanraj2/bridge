import XCTest
@testable import BridgeDomain

final class TelemetryManagerTests: XCTestCase {
    func testTelemetryInstallIdPersistenceAndLaunchCount() {
        let testDefaults = UserDefaults(suiteName: "dev.bridge.test.telemetry")!
        testDefaults.removePersistentDomain(forName: "dev.bridge.test.telemetry")

        let manager1 = TelemetryManager(userDefaults: testDefaults)
        let initialId = manager1.installId
        XCTAssertFalse(initialId.isEmpty)
        XCTAssertTrue(manager1.isEnabled)
        XCTAssertEqual(manager1.launchCount, 0)

        manager1.recordLaunch(version: "0.1.14")
        XCTAssertEqual(manager1.launchCount, 1)

        // Second instance reusing the same user defaults should retain install ID
        let manager2 = TelemetryManager(userDefaults: testDefaults)
        XCTAssertEqual(manager2.installId, initialId)
        XCTAssertEqual(manager2.launchCount, 1)

        manager2.isEnabled = false
        manager2.recordLaunch(version: "0.1.14")
        XCTAssertEqual(manager2.launchCount, 1) // Should not increment when disabled

        let snapshot = manager2.getMetricsSnapshot()
        XCTAssertEqual(snapshot["install_id"] as? String, initialId)
        XCTAssertEqual(snapshot["telemetry_enabled"] as? Bool, false)
    }
}
