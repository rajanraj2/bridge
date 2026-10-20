package dev.bridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bridge.clipboard.ClipboardDestinationAdapter
import dev.bridge.clipboard.ClipboardPolicy
import dev.bridge.clipboard.ClipboardProcessor
import dev.bridge.domain.model.ConnectionState
import dev.bridge.domain.statemachine.ConnectionEvent
import dev.bridge.domain.statemachine.ConnectionStateMachine
import dev.bridge.network.diagnostics.DiagnosticCategory
import dev.bridge.network.diagnostics.DiagnosticLogger
import dev.bridge.network.diagnostics.PermissionAdvisor
import dev.bridge.network.diagnostics.PermissionState
import dev.bridge.network.discovery.PeerDiscoveryService
import dev.bridge.network.endpoint.ConnectionRouteType
import dev.bridge.network.endpoint.EndpointRouter
import dev.bridge.network.endpoint.InMemoryRemoteEndpointStore
import dev.bridge.network.endpoint.RemoteEndpointConfig
import dev.bridge.network.socket.BridgeSocketClient
import dev.bridge.network.socket.BridgeSocketServer
import dev.bridge.network.socket.SocketConnectionListener
import dev.bridge.notifications.NotificationFilterPolicy
import dev.bridge.notifications.NotificationProcessor
import dev.bridge.protocol.v1.Envelope
import dev.bridge.security.IdentityManager
import dev.bridge.security.InMemoryTrustedPeerStore
import dev.bridge.security.PairingChallengePayload
import dev.bridge.security.PairingCoordinator
import dev.bridge.security.TrustedPeer
import dev.bridge.transfers.FileTransferManager
import dev.bridge.transfers.TextSharingService
import dev.bridge.transfers.TransferProgress
import dev.bridge.transfers.TransferStatus
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class MainActivity : ComponentActivity() {

    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val prefs = getSharedPreferences("bridge_identity", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        var publicKeyBase64 = prefs.getString("public_key", null)

        if (deviceId == null || publicKeyBase64 == null) {
            deviceId = IdentityManager.generateDeviceId()
            val keyBytes = ByteArray(32)
            SecureRandom().nextBytes(keyBytes)
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyBytes)
            prefs.edit()
                .putString("device_id", deviceId)
                .putString("public_key", publicKeyBase64)
                .apply()
        }

        val localPublicKey = Base64.getDecoder().decode(publicKeyBase64)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BridgeDashboard(
                        context = this,
                        localDeviceId = deviceId,
                        localPublicKey = localPublicKey,
                        clipboardManager = clipboardManager
                    )
                }
            }
        }
    }
}

@Composable
fun BridgeDashboard(
    context: Context,
    localDeviceId: String,
    localPublicKey: ByteArray,
    clipboardManager: ClipboardManager,
    modifier: Modifier = Modifier
) {
    val stateMachine = remember { ConnectionStateMachine() }
    val peerStore = remember { InMemoryTrustedPeerStore() }
    val pairingCoordinator = remember { PairingCoordinator(peerStore) }

    val discoveryService = remember { PeerDiscoveryService() }
    val endpointStore = remember { InMemoryRemoteEndpointStore() }
    val router = remember { EndpointRouter(discoveryService, endpointStore) }

    val downloadsDir = remember {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
    }
    val transferManager = remember { FileTransferManager(destinationDirectory = downloadsDir) }
    val textSharingService = remember { TextSharingService() }
    val diagnosticLogger = remember { DiagnosticLogger.shared }

    val clipboardAdapter = remember {
        object : ClipboardDestinationAdapter {
            override fun setClipboardText(text: String) {
                val clip = ClipData.newPlainText("Bridge", text)
                clipboardManager.setPrimaryClip(clip)
            }
        }
    }
    val clipboardProcessor = remember { ClipboardProcessor(destinationAdapter = clipboardAdapter) }
    val notificationProcessor = remember { NotificationProcessor() }

    var connectionState by remember { mutableStateOf(stateMachine.state) }
    var activeRouteType by remember { mutableStateOf<ConnectionRouteType?>(null) }
    var activeChallenge by remember { mutableStateOf<PairingChallengePayload?>(null) }
    var sasCode by remember { mutableStateOf<String?>(null) }
    var pairedDevices by remember { mutableStateOf(peerStore.allPeers()) }

    var notificationsEnabled by remember { mutableStateOf(true) }
    var clipboardEnabled by remember { mutableStateOf(true) }
    var tailscaleHostInput by remember { mutableStateOf("") }
    var targetMacIpInput by remember { mutableStateOf("") }
    var lastActionLog by remember { mutableStateOf<String?>(null) }
    var exportedBundleJson by remember { mutableStateOf<String?>(null) }

    var shareInputText by remember { mutableStateOf("") }
    var activeTransferProgress by remember { mutableStateOf<TransferProgress?>(null) }
    var activeTransferId by remember { mutableStateOf<String?>(null) }

    // Live socket server and client references
    var socketServer by remember { mutableStateOf<BridgeSocketServer?>(null) }
    var socketClient by remember { mutableStateOf<BridgeSocketClient?>(null) }

    val localIps = remember { BridgeSocketServer.getLocalIpAddresses() }

    val socketListener = remember {
        object : SocketConnectionListener {
            override fun onConnected(remoteAddress: String) {
                activeRouteType = if (remoteAddress.startsWith("100.")) ConnectionRouteType.REMOTE_TAILSCALE else ConnectionRouteType.LOCAL_LAN
                connectionState = stateMachine.handle(ConnectionEvent.PairingSucceeded)
                BridgeForegroundService.startService(context, remoteAddress)
                diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Socket connected: $remoteAddress")
            }

            override fun onEnvelopeReceived(envelope: Envelope) {
                if (envelope.hasClipboardUpdate()) {
                    clipboardProcessor.handleRemoteUpdate(envelope, localDeviceId)
                }
                if (envelope.hasShareText()) {
                    textSharingService.handleIncomingShare(envelope)
                }
                if (envelope.hasFileRequest()) {
                    val acceptEnv = transferManager.handleIncomingRequest(envelope, localDeviceId, "live-session", autoAccept = true)
                    socketServer?.sendEnvelope(acceptEnv) ?: socketClient?.sendEnvelope(acceptEnv)
                }
                if (envelope.hasFileChunk()) {
                    val (progress, _) = transferManager.handleIncomingChunk(envelope)
                    activeTransferProgress = progress
                }
            }

            override fun onDisconnected(reason: String) {
                connectionState = stateMachine.handle(ConnectionEvent.Disconnect)
                activeRouteType = null
                BridgeForegroundService.stopService(context)
                diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Socket disconnected: $reason")
            }
        }
    }

    // Connect notification listener service to processor
    BridgeNotificationListenerService.activeProcessor = notificationProcessor
    BridgeNotificationListenerService.localDeviceId = localDeviceId
    BridgeNotificationListenerService.activeSessionId = "live-session"

    // Clipboard listener hook
    DisposableEffect(clipboardEnabled) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (clipboardEnabled) {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank()) {
                        val env = clipboardProcessor.handleLocalCopy(text, localDeviceId, "live-session")
                        if (env != null) {
                            socketServer?.sendEnvelope(env) ?: socketClient?.sendEnvelope(env)
                            lastActionLog = "Synced clipboard: ${text.take(30)}..."
                        }
                    }
                }
            }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Bridge",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Local-first Android + macOS Continuity",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Device identity card with real IP
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Local Device Identity & Network IP",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "ID: $localDeviceId",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = "IP: " + if (localIps.isNotEmpty()) localIps.joinToString(", ") else "127.0.0.1",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Fingerprint: " + IdentityManager.fingerprint(localPublicKey),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection status card with Active Route
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connection Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = connectionState.name,
                        fontWeight = FontWeight.Bold,
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                            ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.error
                            ConnectionState.PAIRING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                activeRouteType?.let { route ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Route: " + if (route == ConnectionRouteType.LOCAL_LAN) "Local Wi-Fi / LAN" else "Remote Tailscale (100.x)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            // Start real background TCP server
                            socketServer?.close()
                            val server = BridgeSocketServer(54884, socketListener)
                            server.start()
                            socketServer = server

                            val challenge = pairingCoordinator.createChallenge(
                                deviceId = localDeviceId,
                                displayName = "Pixel Phone",
                                publicKey = localPublicKey,
                                port = 54884
                            )
                            activeChallenge = challenge
                            sasCode = pairingCoordinator.computeSAS(
                                localIdentity = localPublicKey,
                                peerIdentity = localPublicKey,
                                challenge = challenge.challenge
                            )
                            connectionState = stateMachine.handle(ConnectionEvent.StartDiscovery)
                            diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Server listening on port 54884")
                            lastActionLog = "Bridge Server listening on port 54884"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Server / Pair")
                    }

                    OutlinedButton(
                        onClick = {
                            activeChallenge = null
                            sasCode = null
                            activeRouteType = null
                            socketServer?.close()
                            socketServer = null
                            socketClient?.close()
                            socketClient = null
                            connectionState = stateMachine.handle(ConnectionEvent.Disconnect)
                            BridgeForegroundService.stopService(context)
                            diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Disconnected by user")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        // Direct connect to Mac IP
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Connect Directly to Mac",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetMacIpInput,
                    onValueChange = { targetMacIpInput = it },
                    label = { Text("Mac IP Address (e.g. 192.168.1.X or 100.X)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (targetMacIpInput.isNotBlank()) {
                            Thread {
                                socketClient?.close()
                                val client = BridgeSocketClient(socketListener)
                                val success = client.connect(targetMacIpInput.trim(), 54884)
                                if (success) {
                                    socketClient = client
                                    val hello = Envelope.newBuilder()
                                        .setMessageId("hello-1")
                                        .setSessionId("live-session")
                                        .setSenderDeviceId(localDeviceId)
                                        .build()
                                    client.sendEnvelope(hello)
                                    lastActionLog = "Connected to Mac at ${targetMacIpInput.trim()}!"
                                } else {
                                    lastActionLog = "Connection to ${targetMacIpInput.trim()} failed."
                                }
                            }.start()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect to Mac")
                }
            }
        }

        // Active pairing challenge banner
        activeChallenge?.let { challenge ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pairing Challenge Active",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connect from Mac using IP: " + (localIps.firstOrNull() ?: "127.0.0.1"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    sasCode?.let { code ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "SAS: $code",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val trusted = pairingCoordinator.completePairing(
                                    pairingId = challenge.pairingId,
                                    peerDeviceId = "mac-peer",
                                    peerDisplayName = "MacBook",
                                    peerPublicKey = localPublicKey,
                                    userConfirmed = true
                                )
                                pairedDevices = peerStore.allPeers()
                                activeChallenge = null
                                sasCode = null
                                connectionState = stateMachine.handle(ConnectionEvent.PairingSucceeded)
                                activeRouteType = ConnectionRouteType.LOCAL_LAN
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Confirm Match")
                        }

                        OutlinedButton(
                            onClick = {
                                activeChallenge = null
                                sasCode = null
                                connectionState = stateMachine.handle(ConnectionEvent.PairingFailed("User cancelled"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Remote Tailscale Endpoint Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Tailscale Remote Route",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Direct connection fallback over user-managed Tailscale mesh",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = tailscaleHostInput,
                    onValueChange = { tailscaleHostInput = it },
                    label = { Text("Tailscale IP (100.x) or MagicDNS") },
                    placeholder = { Text("100.x.y.z or mac.magicdns.net") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (tailscaleHostInput.isNotBlank()) {
                            endpointStore.saveEndpoint(
                                RemoteEndpointConfig(
                                    deviceId = "mac-sample-peer",
                                    tailscaleHost = tailscaleHostInput.trim()
                                )
                            )
                            val resolved = router.resolveRoute("mac-sample-peer")
                            activeRouteType = resolved?.routeType
                            diagnosticLogger.log(DiagnosticCategory.ROUTE_SELECTION, "Configured Tailscale endpoint: $tailscaleHostInput")
                            lastActionLog = "Tailscale endpoint saved: ${tailscaleHostInput.trim()}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Tailscale Route")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Paired devices section
        Text(
            text = "Paired Devices (${pairedDevices.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (pairedDevices.isEmpty()) {
            Text(
                text = "No paired devices yet. Tap 'Start Server / Pair' to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            pairedDevices.forEach { peer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = peer.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = peer.publicKeyFingerprint,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                pairingCoordinator.revokePeer(peer.deviceId)
                                pairedDevices = peerStore.allPeers()
                                diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Revoked peer ${peer.deviceId}")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Revoke", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // File and Link Sharing Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "File & Link Sharing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Saved automatically to Downloads folder (${downloadsDir.name})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = shareInputText,
                    onValueChange = { shareInputText = it },
                    label = { Text("URL or text to send") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (shareInputText.isNotBlank()) {
                                val isUrl = shareInputText.startsWith("http://") || shareInputText.startsWith("https://")
                                val env = textSharingService.createShareEnvelope(
                                    content = shareInputText,
                                    isUrl = isUrl,
                                    senderDeviceId = localDeviceId,
                                    sessionId = "live-session"
                                )
                                socketServer?.sendEnvelope(env) ?: socketClient?.sendEnvelope(env)
                                lastActionLog = if (isUrl) "URL sent to Mac!" else "Text sent to Mac!"
                                shareInputText = ""
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send Link / Text")
                    }

                    Button(
                        onClick = {
                            val sample = File(context.cacheDir, "sample_doc.txt")
                            sample.writeText("Bridge Continuity Document - Shared from Android phone.")
                            val (reqEnv, transferId) = transferManager.initiateTransfer(sample, localDeviceId, "live-session")
                            activeTransferId = transferId
                            socketServer?.sendEnvelope(reqEnv) ?: socketClient?.sendEnvelope(reqEnv)
                            lastActionLog = "File transfer started: ${sample.name}"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send File")
                    }
                }

                activeTransferProgress?.let { progress ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Transfer: ${progress.status.name} (${progress.bytesTransferred} / ${progress.totalBytes} bytes)",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.fractionCompleted.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Capabilities & Toggles Section
        Text(
            text = "Capabilities & Permissions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Notifications Toggle
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Notification Mirroring", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Forwards non-sensitive notifications to paired Mac",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = {
                            notificationsEnabled = it
                            notificationProcessor.filterPolicy = NotificationFilterPolicy(enabled = it)
                        }
                    )
                }
            }
        }

        // Clipboard Toggle
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Clipboard Synchronization", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Syncs text clipboard bidirectionally with echo-loop prevention",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = clipboardEnabled,
                        onCheckedChange = {
                            clipboardEnabled = it
                            clipboardProcessor.policy = ClipboardPolicy(enabled = it)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = clipboardProcessor.policy.diagnosticStatus,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Permission Remediation Advisor Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Permission & Reliability Advisor",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                PermissionAdvisor.getStandardAndroidChecks().forEach { check ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = check.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = check.state.name,
                                color = if (check.state == PermissionState.GRANTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = check.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (check.state != PermissionState.GRANTED) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Fix: " + check.remediationGuidance,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Diagnostic Bundle Export Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Diagnostic Logs (Privacy-Preserving)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Exports structured connection transitions and metrics with 100% content redaction",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        exportedBundleJson = diagnosticLogger.exportDiagnosticBundle()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate Redacted Diagnostic Bundle")
                }

                exportedBundleJson?.let { json ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = json,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }

        lastActionLog?.let { log ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = log,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
