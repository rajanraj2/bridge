import Foundation
import BridgeDomain
import BridgeProtocol
import BridgeSecurity
import BridgeNetwork
import BridgeNotifications
import BridgeClipboard
import BridgeTransfers

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
    @Published public var activeRouteType: ConnectionRouteType? = nil
    @Published public var activeSAS: String? = nil
    @Published public var activePairingId: String? = nil
    @Published public var pairedPeers: [TrustedPeer] = []
    @Published public var tailscaleHost: String = ""

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

    @Published public var lastTransferLog: String? = nil
    @Published public var exportedDiagnosticBundle: String? = nil

    public let deviceId: String
    public let localPublicKey: Data
    public let peerStore: InMemoryTrustedPeerStore
    public let stateMachine: ConnectionStateMachine
    public let pairingCoordinator: PairingCoordinator
    public let discoveryService: PeerDiscoveryService
    public let endpointStore: InMemoryRemoteEndpointStore
    public let router: EndpointRouter
    public let notificationService: NotificationService
    public let clipboardService: ClipboardService
    public let transferManager: FileTransferManager
    public let textSharingService: TextSharingService
    public let diagnosticLogger: DiagnosticLogger

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
        let disc = PeerDiscoveryService()
        self.discoveryService = disc
        let epStore = InMemoryRemoteEndpointStore()
        self.endpointStore = epStore
        self.router = EndpointRouter(discoveryService: disc, endpointStore: epStore)
        self.notificationService = NotificationService()
        self.clipboardService = ClipboardService()
        self.transferManager = FileTransferManager()
        self.textSharingService = TextSharingService()
        self.diagnosticLogger = DiagnosticLogger.shared
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
        diagnosticLogger.log(category: .connectionState, message: "Pairing initiated")
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
            activeRouteType = .localLAN
            diagnosticLogger.log(category: .handshake, message: "Pairing confirmed with Pixel 8 Pro")
        } catch {
            activePairingId = nil
            activeSAS = nil
            state = stateMachine.handle(event: .pairingFailed(error.localizedDescription))
            diagnosticLogger.log(category: .error, message: "Pairing failed", details: ["reason": error.localizedDescription])
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
        diagnosticLogger.log(category: .connectionState, message: "Peer revoked", details: ["peerId": deviceId])
    }

    public func disconnect() {
        state = stateMachine.handle(event: .disconnect)
        connectedPeerName = nil
        activeRouteType = nil
        diagnosticLogger.log(category: .connectionState, message: "Disconnected by user")
    }

    public func saveTailscaleEndpoint() {
        guard !tailscaleHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        endpointStore.saveEndpoint(
            RemoteEndpointConfig(
                deviceId: "android-sample-peer",
                tailscaleHost: tailscaleHost.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        )
        let resolved = router.resolveRoute(for: "android-sample-peer")
        activeRouteType = resolved?.routeType
        diagnosticLogger.log(category: .routeSelection, message: "Saved Tailscale remote endpoint: \(tailscaleHost)")
    }

    public func exportLogs() {
        exportedDiagnosticBundle = diagnosticLogger.exportDiagnosticBundle()
    }

    public func sendSampleFile() {
        let tempFile = FileManager.default.temporaryDirectory.appendingPathComponent("mac_sample_doc.pdf")
        try? "Sample PDF Document Bytes".data(using: .utf8)?.write(to: tempFile)
        do {
            let (_, transferId) = try transferManager.initiateTransfer(
                sourceURL: tempFile,
                senderDeviceId: deviceId,
                sessionId: "active-session"
            )
            lastTransferLog = "File sent: \(tempFile.lastPathComponent) (\(transferId))"
            diagnosticLogger.log(category: .transferMetric, message: "Outbound file transfer: \(tempFile.lastPathComponent)")
        } catch {
            lastTransferLog = "Transfer error: \(error.localizedDescription)"
            diagnosticLogger.log(category: .error, message: "Transfer initiation failed", details: ["error": error.localizedDescription])
        }
    }

    public func sendUrl(_ urlString: String) {
        do {
            _ = try textSharingService.createShareEnvelope(
                content: urlString,
                isURL: true,
                senderDeviceId: deviceId,
                sessionId: "active-session"
            )
            lastTransferLog = "URL sent: \(urlString)"
            diagnosticLogger.log(category: .transferMetric, message: "URL shared")
        } catch {
            lastTransferLog = "Share error: \(error.localizedDescription)"
        }
    }
}

struct BridgeMenuView: View {
    @ObservedObject var model: BridgeAppModel
    @State private var shareInput: String = ""

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

            if let route = model.activeRouteType {
                HStack {
                    Image(systemName: route == .localLAN ? "wifi" : "network")
                        .foregroundColor(.blue)
                    Text(route == .localLAN ? "Local Wi-Fi" : "Tailscale Remote")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            if let peer = model.connectedPeerName {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                    Text("Connected to: \(peer)")
                        .font(.subheadline)
                }

                Divider()

                VStack(alignment: .leading, spacing: 6) {
                    Text("Share to Phone")
                        .font(.caption)
                        .bold()
                    TextField("Enter URL or text...", text: $shareInput)
                        .textFieldStyle(.roundedBorder)
                    HStack {
                        Button("Send Text/Link") {
                            if !shareInput.isEmpty {
                                model.sendUrl(shareInput)
                                shareInput = ""
                            }
                        }
                        Button("Send File...") {
                            model.sendSampleFile()
                        }
                    }
                }

                if let log = model.lastTransferLog {
                    Text(log)
                        .font(.caption2)
                        .foregroundColor(.secondary)
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
        .frame(width: 300)
    }
}

struct BridgeSettingsView: View {
    @ObservedObject var model: BridgeAppModel

    var body: some View {
        ScrollView {
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

                Text("Tailscale Remote Endpoint")
                    .font(.headline)
                Text("Configure Tailscale IP (100.x) or MagicDNS hostname for remote fallback:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                HStack {
                    TextField("100.x.y.z or phone.magicdns.net", text: $model.tailscaleHost)
                        .textFieldStyle(.roundedBorder)
                    Button("Save Route") {
                        model.saveTailscaleEndpoint()
                    }
                }

                Divider()

                Text("Capabilities")
                    .font(.headline)
                Toggle("Mirror Android notifications to macOS", isOn: $model.notificationsEnabled)
                Toggle("Bidirectional clipboard synchronization", isOn: $model.clipboardEnabled)

                Divider()

                Text("Permissions & Remediation")
                    .font(.headline)
                ForEach(PermissionAdvisor.evaluateMacPermissions()) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(item.name).bold()
                            Spacer()
                            Text(item.status.rawValue.capitalized)
                                .font(.caption)
                                .foregroundColor(.green)
                        }
                        Text(item.description)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 2)
                }

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

                Divider()

                Text("Privacy & Diagnostics")
                    .font(.headline)
                Button("Export Redacted Diagnostic Bundle") {
                    model.exportLogs()
                }
                if let logs = model.exportedDiagnosticBundle {
                    Text(logs)
                        .font(.system(size: 9, design: .monospaced))
                        .padding(8)
                        .background(Color.gray.opacity(0.1))
                        .cornerRadius(4)
                }

                Spacer()
            }
            .padding(24)
        }
        .frame(width: 520, height: 480)
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

        _ = NotificationService()
        _ = ClipboardService()
        _ = FileTransferManager()

        let epStore = InMemoryRemoteEndpointStore()
        epStore.saveEndpoint(RemoteEndpointConfig(deviceId: "peer-1", tailscaleHost: "100.100.100.100"))
        let disc = PeerDiscoveryService()
        let router = EndpointRouter(discoveryService: disc, endpointStore: epStore)
        let route = router.resolveRoute(for: "peer-1")
        print("Resolved Route: \(route?.routeType.rawValue ?? "none") to \(route?.host ?? "")")

        let logger = DiagnosticLogger.shared
        logger.log(category: .connectionState, message: "M4 Remote and Polish verification complete")
        let bundle = logger.exportDiagnosticBundle()
        print("Exported Redacted Diagnostic Bundle (\(bundle.count) bytes)")
    }
}
#endif
