import Foundation

public enum PermissionStatus: String, Codable, Sendable {
    case granted
    case denied
    case restricted
    case notDetermined
}

public struct PermissionItem: Identifiable, Sendable {
    public var id: String { name }
    public let name: String
    public let status: PermissionStatus
    public let description: String
    public let remediationGuidance: String
}

public struct PermissionAdvisor {
    public static func evaluateMacPermissions() -> [PermissionItem] {
        return [
            PermissionItem(
                name: "Local Network",
                status: .granted,
                description: "Allows discovering and connecting to Android peers over Wi-Fi.",
                remediationGuidance: "Open System Settings > Privacy & Security > Local Network and ensure Bridge is enabled."
            ),
            PermissionItem(
                name: "Notifications",
                status: .granted,
                description: "Enables displaying native mirrored notifications from Android.",
                remediationGuidance: "Open System Settings > Notifications > Bridge and toggle Allow Notifications to ON."
            ),
            PermissionItem(
                name: "Launch at Login",
                status: .notDetermined,
                description: "Keeps Bridge running in the background menu bar across reboots.",
                remediationGuidance: "Enable 'Start at Login' in Bridge Preferences or System Settings > General > Login Items."
            )
        ]
    }
}
