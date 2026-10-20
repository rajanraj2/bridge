import Foundation
import BridgeDomain

public enum DiagnosticCategory: String, Codable, Sendable {
    case connectionState
    case handshake
    case heartbeat
    case routeSelection
    case networkSwitch
    case error
    case transferMetric
}

public struct DiagnosticEvent: Codable, Equatable, Sendable {
    public let timestampUnixMs: Int64
    public let category: DiagnosticCategory
    public let message: String
    public let details: [String: String]

    public init(category: DiagnosticCategory, message: String, details: [String: String] = [:]) {
        self.timestampUnixMs = Int64(Date().timeIntervalSince1970 * 1000)
        self.category = category
        self.message = message
        self.details = details
    }
}

public final class DiagnosticLogger: @unchecked Sendable {
    public static let shared = DiagnosticLogger()
    public static let maxEvents: Int = 500

    private let lock = NSLock()
    private var events: [DiagnosticEvent] = []

    public init() {}

    /// Logs a structured diagnostic event. Ensures no sensitive payload content is included.
    public func log(category: DiagnosticCategory, message: String, details: [String: String] = [:]) {
        lock.lock()
        defer { lock.unlock() }

        // Sanitize: strip any accidental keys containing sensitive values
        var cleanDetails: [String: String] = [:]
        for (key, value) in details {
            let lower = key.lowercased()
            if lower.contains("text") || lower.contains("title") || lower.contains("body") ||
               lower.contains("secret") || lower.contains("key") || lower.contains("token") || lower.contains("payload") {
                cleanDetails[key] = "[REDACTED]"
            } else {
                cleanDetails[key] = value
            }
        }

        let event = DiagnosticEvent(category: category, message: message, details: cleanDetails)
        events.append(event)
        if events.count > Self.maxEvents {
            events.removeFirst(events.count - Self.maxEvents)
        }
    }

    public func getEvents() -> [DiagnosticEvent] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        events.removeAll()
    }

    /// Generates a sanitized diagnostic bundle string in JSON format.
    public func exportDiagnosticBundle(
        appVersion: String = "0.1.0",
        osVersion: String = ProcessInfo.processInfo.operatingSystemVersionString
    ) -> String {
        lock.lock()
        let eventList = events
        lock.unlock()

        let bundle: [String: Any] = [
            "bridgeAppVersion": appVersion,
            "os": "macOS",
            "osVersion": osVersion,
            "exportTimestamp": Int64(Date().timeIntervalSince1970 * 1000),
            "eventCount": eventList.count,
            "events": eventList.map { [
                "time": $0.timestampUnixMs,
                "category": $0.category.rawValue,
                "message": $0.message,
                "details": $0.details
            ]}
        ]

        if let data = try? JSONSerialization.data(withJSONObject: bundle, options: .prettyPrinted),
           let json = String(data: data, encoding: .utf8) {
            return json
        }

        return "{}"
    }
}
