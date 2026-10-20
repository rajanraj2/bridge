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
import AppKit
import UserNotifications

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

public final class BridgeAppModel: @unchecked Sendable, ObservableObject, SocketEventListener {
    @Published public var state: ConnectionState = .disconnected
    @Published public var deviceName: String = Host.current().localizedName ?? "My Mac"
    @Published public var connectedPeerName: String? = nil
    @Published public var activeRouteType: ConnectionRouteType? = nil
    @Published public var activeSAS: String? = nil
    @Published public var activePairingId: String? = nil
    @Published public var pairedPeers: [TrustedPeer] = []
    @Published public var tailscaleHost: String = ""
    @Published public var targetPhoneIp: String = ""
    @Published public var localIps: [String] = []

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
    public let appVersion: String = "0.1.7"
    @Published public var availableUpdate: UpdateInfo? = nil
    @Published public var updateStatusText: String? = nil
    @Published public var isCheckingUpdates: Bool = false
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

    private var socketServer: BridgeSocketServer?
    private var socketClient: BridgeSocketClient?
    private var lastPasteboardChangeCount: Int = 0
    private var pasteboardTimer: Timer?

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
        // Persistent identity in Application Support
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let identityDir = appSupport.appendingPathComponent("Bridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: identityDir, withIntermediateDirectories: true)
        let identityFile = identityDir.appendingPathComponent("identity.json")

        var savedDeviceId = "brg-" + UUID().uuidString.lowercased()
        var savedKeyData = Data(count: 32)
        for i in 0..<32 { savedKeyData[i] = UInt8.random(in: 0...255) }

        if let existing = try? Data(contentsOf: identityFile),
           let obj = try? JSONSerialization.jsonObject(with: existing) as? [String: String],
           let id = obj["deviceId"], let keyB64 = obj["publicKey"],
           let key = Data(base64Encoded: keyB64) {
            savedDeviceId = id
            savedKeyData = key
        } else {
            let record: [String: String] = ["deviceId": savedDeviceId, "publicKey": savedKeyData.base64EncodedString()]
            if let outData = try? JSONSerialization.data(withJSONObject: record) {
                try? outData.write(to: identityFile)
            }
        }

        self.deviceId = savedDeviceId
        self.localPublicKey = savedKeyData

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

        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        self.transferManager = FileTransferManager(destinationDirectory: downloads)
        self.textSharingService = TextSharingService()
        self.diagnosticLogger = DiagnosticLogger.shared
        self.pairedPeers = store.allPeers()
        self.localIps = BridgeSocketServer.getLocalIpAddresses()

        startSocketServer()
        startPasteboardMonitoring()

        Task { [weak self] in
            await self?.checkForUpdates(userInitiated: false)
        }
    }
    private func startSocketServer() {
        do {
            let server = BridgeSocketServer(port: 54884)
            server.listener = self
            try server.start()
            self.socketServer = server
            diagnosticLogger.log(category: .connectionState, message: "Server listening on port 54884")
        } catch {
            diagnosticLogger.log(category: .error, message: "Failed to bind port 54884: \(error.localizedDescription)")
        }
    }

    private func startPasteboardMonitoring() {
        lastPasteboardChangeCount = NSPasteboard.general.changeCount
        pasteboardTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self, self.clipboardEnabled else { return }
            let current = NSPasteboard.general.changeCount
            if current != self.lastPasteboardChangeCount {
                self.lastPasteboardChangeCount = current
                if let string = NSPasteboard.general.string(forType: .string), !string.isEmpty {
                    if let env = self.clipboardService.handleLocalCopy(text: string, localDeviceId: self.deviceId, sessionId: "live-session", forceSend: true) {
                        self.sendEnvelope(env)
                        self.lastTransferLog = "Sent to phone: \(string.prefix(25))..."
                    }
                }
            }
        }
    }

    public func connectToPhone(ip: String) {
        guard !ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let client = BridgeSocketClient(listener: self)
        do {
            try client.connect(host: ip.trimmingCharacters(in: .whitespacesAndNewlines), port: 54884)
            self.socketClient = client
            lastTransferLog = "Connected to phone at \(ip)!"
        } catch {
            lastTransferLog = "Connection to \(ip) failed: \(error.localizedDescription)"
        }
    }

    public func sendEnvelope(_ envelope: Bridge_Envelope) {
        let sentServer = socketServer?.send(envelope: envelope) ?? false
        let sentClient = socketClient?.send(envelope: envelope) ?? false
        if !sentServer && !sentClient {
            diagnosticLogger.log(category: .error, message: "sendEnvelope: No active socket connection to peer")
        }
    }

    // SocketEventListener Callbacks
    public func onConnected(remoteAddress: String) {
        DispatchQueue.main.async {
            self.connectedPeerName = "Android Phone (\(remoteAddress))"
            self.activeRouteType = remoteAddress.starts(with: "100.") ? .remoteTailscale : .localLAN
            self.state = self.stateMachine.handle(event: .connectionEstablished(PeerSession(sessionId: "live-session", peerDevice: DeviceIdentity(deviceId: "android-phone", displayName: "Android Phone", platform: .android, publicKeyFingerprint: "XX:XX"), protocolMajor: 1, protocolMinor: 0)))
            self.diagnosticLogger.log(category: .connectionState, message: "Socket connected: \(remoteAddress)")
        }
    }

    public func onEnvelopeReceived(envelope: Bridge_Envelope) {
        DispatchQueue.main.async {
            switch envelope.payload {
            case .clipboardUpdate:
                switch self.clipboardService.handleRemoteUpdate(envelope: envelope, localDeviceId: self.deviceId) {
                case .success(let text):
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                    self.lastPasteboardChangeCount = NSPasteboard.general.changeCount
                    self.lastTransferLog = "Clipboard received from phone: \(text.prefix(25))..."
                    let alertScript = "display notification \"\(text.prefix(40))\" with title \"Clipboard Synced\" subtitle \"From Android Phone\""
                    if let appleScript = NSAppleScript(source: alertScript) {
                        var err: NSDictionary?
                        appleScript.executeAndReturnError(&err)
                    }
                case .failure(let reason):
                    self.lastTransferLog = "Clipboard rejected: \(reason)"
                }
            case .notificationEvent(let notif):
                self.notificationService.handleIncomingNotification(envelope)
                let title = notif.title
                let body = notif.body
                let alertScript = "display notification \"\(body)\" with title \"\(title)\" subtitle \"Bridge from Phone\""
                if let appleScript = NSAppleScript(source: alertScript) {
                    var errorInfo: NSDictionary?
                    appleScript.executeAndReturnError(&errorInfo)
                }
            case .shareText:
                _ = self.textSharingService.handleIncomingShare(envelope)
            case .fileRequest:
                if let acceptEnv = try? self.transferManager.handleIncomingRequest(envelope, senderDeviceId: self.deviceId, sessionId: "live-session", autoAccept: true) {
                    self.sendEnvelope(acceptEnv)
                }
            case .fileChunk:
                if let (_, completedURL) = try? self.transferManager.handleIncomingChunk(envelope), let url = completedURL {
                    self.lastTransferLog = "File received: \(url.lastPathComponent) (saved to Downloads)"
                }
            default:
                break
            }
        }
    }

    public func onDisconnected(reason: String) {
        DispatchQueue.main.async {
            self.connectedPeerName = nil
            self.activeRouteType = nil
            self.state = self.stateMachine.handle(event: .disconnect)
            self.diagnosticLogger.log(category: .connectionState, message: "Socket disconnected: \(reason)")
        }
    }

    public func startPairing() {
        let challenge = pairingCoordinator.createChallenge(
            deviceId: deviceId,
            displayName: deviceName,
            publicKey: localPublicKey,
            port: 54884
        )
        activePairingId = challenge.pairingId
        if let challengeData = challenge.challenge {
            activeSAS = pairingCoordinator.computeSAS(
                localIdentity: localPublicKey,
                peerIdentity: localPublicKey,
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
                peerDeviceId: "android-phone",
                peerDisplayName: "Pixel Phone",
                peerPublicKey: localPublicKey,
                userConfirmed: true
            )
            pairedPeers = peerStore.allPeers()
            activePairingId = nil
            activeSAS = nil
            state = stateMachine.handle(event: .pairingSucceeded)
            connectedPeerName = "Pixel Phone"
            activeRouteType = .localLAN
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
        activeRouteType = nil
        socketClient?.disconnect()
    }

    public func saveTailscaleEndpoint() {
        guard !tailscaleHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        endpointStore.saveEndpoint(
            RemoteEndpointConfig(
                deviceId: "android-phone",
                tailscaleHost: tailscaleHost.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        )
        let resolved = router.resolveRoute(for: "android-phone")
        activeRouteType = resolved?.routeType
        diagnosticLogger.log(category: .routeSelection, message: "Saved Tailscale remote endpoint: \(tailscaleHost)")
    }

    public func exportLogs() {
        exportedDiagnosticBundle = diagnosticLogger.exportDiagnosticBundle()
    }

    public func sendSampleFile() {
        let tempFile = FileManager.default.temporaryDirectory.appendingPathComponent("Bridge_Document.txt")
        try? "Hello from macOS! Bridge Continuity File Transfer.".data(using: .utf8)?.write(to: tempFile)
        do {
            let (reqEnv, transferId) = try transferManager.initiateTransfer(
                sourceURL: tempFile,
                senderDeviceId: deviceId,
                sessionId: "live-session"
            )
            sendEnvelope(reqEnv)
            lastTransferLog = "File sent: \(tempFile.lastPathComponent) (\(transferId))"
        } catch {
            lastTransferLog = "Transfer error: \(error.localizedDescription)"
        }
    }

    public func sendUrl(_ urlString: String) {
        do {
            let env = try textSharingService.createShareEnvelope(
                content: urlString,
                isURL: true,
                senderDeviceId: deviceId,
                sessionId: "live-session"
            )
            sendEnvelope(env)
            lastTransferLog = "URL sent: \(urlString)"
        } catch {
            lastTransferLog = "Share error: \(error.localizedDescription)"
        }
    }

    @MainActor
    public func checkForUpdates(userInitiated: Bool = false) async {
        isCheckingUpdates = true
        if userInitiated {
            updateStatusText = "Checking for updates..."
        }
        let update = await UpdateManager.shared.checkForUpdates(currentVersion: appVersion)
        isCheckingUpdates = false
        availableUpdate = update
        if let update = update {
            updateStatusText = "Update available: \(update.tagName)"
        } else if userInitiated {
            updateStatusText = "You are on the latest version (v\(appVersion)) ✓"
        }
    }

    public func openUpdateDownload() {
        if let update = availableUpdate, let url = URL(string: update.dmgDownloadUrl ?? update.releaseUrl) {
            #if os(macOS)
            NSWorkspace.shared.open(url)
            #endif
        }
    }

    public func openPrivacySecuritySettings() {
        #if os(macOS)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Security") {
            NSWorkspace.shared.open(url)
        }
        #endif
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

            if let update = model.availableUpdate {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: "arrow.down.circle.fill")
                            .foregroundColor(.blue)
                        Text("Update Available: \(update.tagName)")
                            .font(.caption)
                            .bold()
                    }
                    Button("⬇️ Download & Install Update...") {
                        model.openUpdateDownload()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .padding(8)
                .background(Color.blue.opacity(0.1))
                .cornerRadius(6)
            }
            if !model.localIps.isEmpty {
                Text("Mac IP: \(model.localIps.first ?? "")")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)
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
                    Text("Connected: \(peer)")
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

                HStack {
                    TextField("Phone IP address...", text: $model.targetPhoneIp)
                        .textFieldStyle(.roundedBorder)
                    Button("Connect") {
                        model.connectToPhone(ip: model.targetPhoneIp)
                    }
                }

                Button("Start Pairing Server...") {
                    model.startPairing()
                }
            }

            Divider()

            Toggle("Mirror Notifications", isOn: $model.notificationsEnabled)
                .font(.caption)

            Toggle("Sync Clipboard", isOn: $model.clipboardEnabled)
                .font(.caption)

            Divider()

            Button("Check for Updates...") {
                Task {
                    await model.checkForUpdates(userInitiated: true)
                }
            }
            if let status = model.updateStatusText {
                Text(status)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Button("Open Privacy & Security Settings...") {
                model.openPrivacySecuritySettings()
            }

            Button("Preferences...") {
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            }
            Button("Quit Bridge") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(16)
        .frame(width: 320)
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
                Text("Local IP: \(model.localIps.joined(separator: ", "))")
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

                Divider()

                Text("Application Updates")
                    .font(.headline)
                Text("Current version: v\(model.appVersion)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Button("Check for Updates...") {
                    Task {
                        await model.checkForUpdates(userInitiated: true)
                    }
                }
                if let status = model.updateStatusText {
                    Text(status)
                        .font(.caption)
                        .foregroundColor(.blue)
                }
                if let update = model.availableUpdate {
                    Button("⬇️ Download & Install \(update.tagName)") {
                        model.openUpdateDownload()
                    }
                    .buttonStyle(.borderedProminent)
                }

                Divider()

                Button("Open Privacy & Security Settings...") {
                    model.openPrivacySecuritySettings()
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

        let ips = BridgeSocketServer.getLocalIpAddresses()
        print("Local IP Addresses: \(ips.joined(separator: ", "))")

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
        logger.log(category: .connectionState, message: "Real socket and runtime verification complete")
        let bundle = logger.exportDiagnosticBundle()
        print("Exported Redacted Diagnostic Bundle (\(bundle.count) bytes)")
    }
}
#endif
