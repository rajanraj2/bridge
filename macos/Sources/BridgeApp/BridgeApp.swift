import Foundation
import BridgeDomain
import BridgeProtocol
import BridgeSecurity
import BridgeNetwork

#if canImport(SwiftUI) && os(macOS)
import SwiftUI

@main
struct BridgeApp: App {
    @StateObject private var model = BridgeAppModel()

    var body: some Scene {
        MenuBarExtra("Bridge", systemImage: model.statusIconName) {
            BridgeMenuView(model: model)
        }
        .menuBarExtraStyle(.window)

        Window("Bridge Settings", id: "settings") {
            BridgeSettingsView(model: model)
        }
    }
}

public final class BridgeAppModel: ObservableObject {
    @Published public var state: ConnectionState = .disconnected
    @Published public var deviceName: String = Host.current().localizedName ?? "My Mac"
    @Published public var connectedPeerName: String? = nil

    public var statusIconName: String {
        switch state {
        case .connected: return "link.circle.fill"
        case .connecting, .pairing: return "arrow.triangle.2.circlepath.circle"
        case .reconnecting: return "exclamationmark.triangle.fill"
        case .discovering: return "dot.radiowaves.left.and.right"
        case .disconnected: return "link.circle"
        }
    }

    public init() {}
}

struct BridgeMenuView: View {
    @ObservedObject var model: BridgeAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Bridge")
                    .font(.headline)
                Spacer()
                Text(model.state.rawValue.capitalized)
                    .font(.caption)
                    .foregroundColor(model.state == .connected ? .green : .secondary)
            }
            Divider()
            if let peer = model.connectedPeerName {
                Text("Connected to: \(peer)")
                    .font(.subheadline)
            } else {
                Text("No device connected")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            Divider()
            Button("Settings...") {
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            }
            Button("Quit Bridge") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(16)
        .frame(width: 260)
    }
}

struct BridgeSettingsView: View {
    @ObservedObject var model: BridgeAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Bridge Preferences")
                .font(.title2)
                .bold()
            Text("Device: \(model.deviceName)")
            Text("Status: \(model.state.rawValue.capitalized)")
            Spacer()
        }
        .padding(24)
        .frame(width: 400, height: 250)
    }
}

#else
// CLI entry point for non-macOS platforms (e.g. Linux verification)
@main
struct BridgeApp {
    static func main() {
        print("Bridge v0.1 — Cross-Platform Core Shell")
        let identity = IdentityManager.generateDeviceId()
        print("Generated Device ID: \(identity)")
        let sm = ConnectionStateMachine()
        print("Initial state: \(sm.state)")
        sm.handle(event: .startDiscovery)
        print("Transitioned state: \(sm.state)")
    }
}
#endif
