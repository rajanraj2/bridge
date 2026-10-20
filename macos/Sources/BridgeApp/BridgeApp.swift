import Foundation
import BridgeDomain
import BridgeProtocol
import BridgeSecurity
import BridgeNetwork
import BridgeNotifications
import BridgeClipboard

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

        Window("Bridge Preferences", id: "settings") {
            BridgeSettingsView(model: model)
        }
    }
}

public final class BridgeAppModel: ObservableObject {
    @Published public var state: ConnectionState = .disconnected
    @Published public var deviceName: String = Host.current().localizedName ?? "My Mac"
    @Published public var connectedPeerName: String? = nil
    @Published public var activeSAS: String? = nil
    @Published public var activePairingId: String? = nil
    @Published public var pairedPeers: [TrustedPeer] = []

    @Published public var notificationsEnabled: Bool = true {
        didSet {
            notificationService.filterPolicy.enabled = notificationsEnabled
        }
    }

    @Published public var clipboardEnabled: Bool = true {
        didSet {
            clipboardService.policy.enabled = clipboardEnabled
        }
    }

    public let deviceId: String
    public let localPublicKey: Data
    public let peerStore: InMemoryTrustedPeerStore
    public let stateMachine: ConnectionStateMachine
    public let pairingCoordinator: PairingCoordinator
    public let notificationService: NotificationService
    public let clipboardService: ClipboardService

    public var statusIconName: String {
        switch state {
        case .connected: return "link.circle.fill"
        case .connecting, .pairing: return "arrow.triangle.2.circlepath.circle"
        case .reconnecting: return "exclamationmark.triangle.fill"
        case .discovering: return "dot.radiowaves.left.and.right"
        case .disconnected: return "link.circle"
        }
    }

    public init() {
        self.deviceId = IdentityManager.generateDeviceId()
        self.localPublicKey = "MacBook-Ed25519-IdentityKeyData".data(using: .utf8)!
        let store = InMemoryTrustedPeerStore()
        self.peerStore = store
        self.stateMachine = ConnectionStateMachine()
        self.pairingCoordinator = PairingCoordinator(store: store)
        self.notificationService = NotificationService()
        self.clipboardService = ClipboardService()
        self.pairedPeers = store.allPeers()
    }

    public func startPairing() {
        let challenge = pairingCoordinator.createChallenge(
            deviceId: deviceId,
            displayName: deviceName,
            publicKey: localPublicKey
        )
        activePairingId = challenge.pairingId
        if let challengeData = challenge.challenge {
            activeSAS = pairingCoordinator.computeSAS(
                localIdentity: localPublicKey,
                peerIdentity: "PeerSampleIdentityKeyData".data(using: .utf8)!,
                challenge: challengeData
            )
        }
        state = stateMachine.handle(event: .startDiscovery)
    }

    public func confirmPairing() {
        guard let pairingId = activePairingId else { return }
        do {
            _ = try pairingCoordinator.completePairing(
                pairingId: pairingId,
                peerDeviceId: "android-sample-peer",
                peerDisplayName: "Pixel 8 Pro",
                peerPublicKey: "PeerSampleIdentityKeyData".data(using: .utf8)!,
                userConfirmed: true
            )
            pairedPeers = peerStore.allPeers()
            activePairingId = nil
            activeSAS = nil
            state = stateMachine.handle(event: .pairingSucceeded)
            connectedPeerName = "Pixel 8 Pro"
        } catch {
            activePairingId = nil
            activeSAS = nil
            state = stateMachine.handle(event: .pairingFailed(error.localizedDescription))
        }
    }

    public func cancelPairing() {
        activePairingId = nil
        activeSAS = nil
        state = stateMachine.handle(event: .disconnect)
    }

    public func revokePeer(deviceId: String) {
        _ = pairingCoordinator.revokePeer(deviceId: deviceId)
        pairedPeers = peerStore.allPeers()
        if connectedPeerName != nil {
            disconnect()
        }
    }

    public func disconnect() {
        state = stateMachine.handle(event: .disconnect)
        connectedPeerName = nil
    }
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
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                    Text("Connected to: \(peer)")
                        .font(.subheadline)
                }
                Button("Disconnect") {
                    model.disconnect()
                }
            } else if let sas = model.activeSAS {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Pairing in Progress")
                        .font(.subheadline)
                        .bold()
                    Text("Confirm SAS code matches on phone:")
                        .font(.caption)
                    Text(sas)
                        .font(.system(size: 24, weight: .bold, design: .monospaced))
                        .foregroundColor(.accentColor)
                    HStack {
                        Button("Confirm Match") {
                            model.confirmPairing()
                        }
                        Button("Cancel") {
                            model.cancelPairing()
                        }
                    }
                }
            } else {
                Text("No device connected")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Button("Pair New Device...") {
                    model.startPairing()
                }
            }

            Divider()

            Toggle("Mirror Notifications", isOn: $model.notificationsEnabled)
                .font(.caption)

            Toggle("Sync Clipboard", isOn: $model.clipboardEnabled)
                .font(.caption)

            Divider()
            Button("Preferences...") {
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            }
            Button("Quit Bridge") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(16)
        .frame(width: 280)
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
            Text("Device ID: \(model.deviceId)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.secondary)
            Text("Status: \(model.state.rawValue.capitalized)")

            Divider()

            Text("Capabilities")
                .font(.headline)
            Toggle("Mirror Android notifications to macOS", isOn: $model.notificationsEnabled)
            Toggle("Bidirectional clipboard synchronization", isOn: $model.clipboardEnabled)

            Divider()

            Text("Paired Devices (\(model.pairedPeers.count))")
                .font(.headline)

            if model.pairedPeers.isEmpty {
                Text("No paired devices.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            } else {
                ForEach(model.pairedPeers) { peer in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(peer.displayName).bold()
                            Text(peer.publicKeyFingerprint)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Button("Revoke") {
                            model.revokePeer(deviceId: peer.deviceId)
                        }
                    }
                }
            }

            Spacer()
        }
        .padding(24)
        .frame(width: 480, height: 380)
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

        let store = InMemoryTrustedPeerStore()
        let coordinator = PairingCoordinator(store: store)
        let key = "MockKeyBytes".data(using: .utf8)!
        let challenge = coordinator.createChallenge(deviceId: identity, displayName: "Host Device", publicKey: key)
        print("Pairing Challenge ID: \(challenge.pairingId)")

        let sas = coordinator.computeSAS(localIdentity: key, peerIdentity: "PeerKey".data(using: .utf8)!, challenge: challenge.challenge!)
        print("SAS Verification Code: \(sas)")

        let notifService = NotificationService()
        print("Notification Service initialized (suppressSensitive=\(notifService.filterPolicy.suppressSensitive))")

        let clipService = ClipboardService()
        print("Clipboard Service initialized (preventEchoLoops=\(clipService.policy.preventEchoLoops))")

        print("Ready for local discovery, pairing, notifications, and clipboard sync.")
    }
}
#endif
