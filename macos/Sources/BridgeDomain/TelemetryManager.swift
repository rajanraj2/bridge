import Foundation

public final class TelemetryManager: @unchecked Sendable {
    public static let shared = TelemetryManager()

    private let userDefaults: UserDefaults
    private let installIdKey = "dev.bridge.telemetry.install_id"
    private let installTimestampKey = "dev.bridge.telemetry.install_timestamp"
    private let telemetryEnabledKey = "dev.bridge.telemetry.enabled"
    private let launchCountKey = "dev.bridge.telemetry.launch_count"

    public private(set) var installId: String
    public private(set) var installTimestamp: Int64
    public var isEnabled: Bool {
        get { userDefaults.bool(forKey: telemetryEnabledKey) }
        set { userDefaults.set(newValue, forKey: telemetryEnabledKey) }
    }
    public private(set) var launchCount: Int

    public init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        if let existingId = userDefaults.string(forKey: installIdKey) {
            self.installId = existingId
        } else {
            let newId = UUID().uuidString
            userDefaults.set(newId, forKey: installIdKey)
            self.installId = newId
        }

        let existingTs = userDefaults.object(forKey: installTimestampKey) as? Int64 ?? 0
        if existingTs > 0 {
            self.installTimestamp = existingTs
        } else {
            let newTs = Int64(Date().timeIntervalSince1970 * 1000)
            userDefaults.set(newTs, forKey: installTimestampKey)
            self.installTimestamp = newTs
        }

        if userDefaults.object(forKey: telemetryEnabledKey) == nil {
            userDefaults.set(true, forKey: telemetryEnabledKey)
        }

        self.launchCount = userDefaults.integer(forKey: launchCountKey)
    }

    public func recordLaunch(version: String, platform: String = "macOS") {
        guard isEnabled else { return }
        launchCount += 1
        userDefaults.set(launchCount, forKey: launchCountKey)
    }

    public func getMetricsSnapshot() -> [String: Any] {
        return [
            "install_id": installId,
            "install_timestamp": installTimestamp,
            "launch_count": launchCount,
            "telemetry_enabled": isEnabled,
            "platform": "macOS"
        ]
    }
}
