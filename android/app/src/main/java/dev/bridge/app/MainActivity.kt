package dev.bridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import dev.bridge.network.discovery.DiscoveredPeer
import dev.bridge.network.discovery.PeerDiscoveryService
import dev.bridge.network.endpoint.ConnectionRouteType
import dev.bridge.network.endpoint.EndpointRouter
import dev.bridge.network.endpoint.InMemoryRemoteEndpointStore
import dev.bridge.network.endpoint.RemoteEndpointConfig
import dev.bridge.network.socket.BridgeConnectionManager
import dev.bridge.network.socket.BridgeSocketServer
import dev.bridge.network.socket.SocketConnectionListener
import dev.bridge.notifications.NotificationFilterPolicy
import dev.bridge.notifications.NotificationForwarder
import dev.bridge.notifications.NotificationProcessor
import dev.bridge.protocol.v1.Envelope
import dev.bridge.security.IdentityManager
import dev.bridge.security.InMemoryTrustedPeerStore
import dev.bridge.security.PairingChallengePayload
import dev.bridge.security.PairingCoordinator
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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val clip = ClipData.newPlainText("Bridge", text)
                        clipboardManager.setPrimaryClip(clip)
                    } catch (e: Exception) {
                        println("clipboardAdapter: setPrimaryClip error: ${e.message}")
                    }
                }
            }
        }
    }
    val clipboardProcessor = remember { ClipboardProcessor(destinationAdapter = clipboardAdapter) }

    val notificationForwarder = remember {
        object : NotificationForwarder {
            override fun sendEnvelope(envelope: Envelope) {
                BridgeConnectionManager.sendEnvelope(envelope)
            }
        }
    }
    val notificationProcessor = remember { NotificationProcessor(forwarder = notificationForwarder) }

    var connectionState by remember { mutableStateOf(if (BridgeConnectionManager.isConnected) ConnectionState.CONNECTED else stateMachine.state) }
    var activeRouteType by remember { mutableStateOf<ConnectionRouteType?>(null) }
    var activeChallenge by remember { mutableStateOf<PairingChallengePayload?>(null) }
    var sasCode by remember { mutableStateOf<String?>(null) }
    var pairedDevices by remember { mutableStateOf(peerStore.allPeers()) }
    var discoveredMacs by remember { mutableStateOf(listOf(
        DiscoveredPeer("mac-lan", "MacBook (Local LAN)", "192.168.1.5", 54884, 1, 0)
    )) }

    var notificationsEnabled by remember { mutableStateOf(true) }
    var clipboardEnabled by remember { mutableStateOf(true) }
    var tailscaleHostInput by remember { mutableStateOf("") }
    var targetMacIpInput by remember { mutableStateOf("192.168.1.5") }
    var lastActionLog by remember { mutableStateOf<String?>(null) }
    var lastReceivedClipboardText by remember { mutableStateOf<String?>(null) }
    var exportedBundleJson by remember { mutableStateOf<String?>(null) }
    val currentAppVersion = "0.1.18"
    val coroutineScope = rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckStatus by remember { mutableStateOf<String?>(null) }
    var shareInputText by remember { mutableStateOf("") }
    var activeTransferProgress by remember { mutableStateOf<TransferProgress?>(null) }
    var activeTransferId by remember { mutableStateOf<String?>(null) }
    var showAdvancedDetails by remember { mutableStateOf(false) }

    fun checkNotificationAccess(): Boolean {
        return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    fun checkAccessibility(): Boolean {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("BridgeAccessibilityService") == true
    }

    fun checkBatteryExemption(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun checkPostNotifications(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    var hasNotificationAccess by remember { mutableStateOf(checkNotificationAccess()) }
    var hasAccessibility by remember { mutableStateOf(checkAccessibility()) }
    var hasBatteryExemption by remember { mutableStateOf(checkBatteryExemption()) }
    var hasPostNotifications by remember { mutableStateOf(checkPostNotifications()) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = checkNotificationAccess()
                hasAccessibility = checkAccessibility()
                hasBatteryExemption = checkBatteryExemption()
                hasPostNotifications = checkPostNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val localIps = remember { BridgeSocketServer.getLocalIpAddresses() }
    val socketListener = remember {
        object : SocketConnectionListener {
            override fun onConnected(remoteAddress: String) {
                activeRouteType = if (remoteAddress.startsWith("100.")) ConnectionRouteType.REMOTE_TAILSCALE else ConnectionRouteType.LOCAL_LAN
                connectionState = ConnectionState.CONNECTED
                BridgeForegroundService.startService(context, remoteAddress)
                diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Socket connected: $remoteAddress")
                lastActionLog = "Connected to $remoteAddress!"
            }

            override fun onEnvelopeReceived(envelope: Envelope) {
                if (envelope.hasClipboardUpdate()) {
                    val update = envelope.clipboardUpdate
                    val text = update.text
                    val hasImage = !update.imageData.isEmpty
                    clipboardProcessor.handleRemotePayload(envelope, localDeviceId)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            if (hasImage) {
                                val fileName = if (update.fileName.isNotBlank()) update.fileName else (if (update.contentType.contains("png")) "image.png" else "shared_file.bin")
                                val sharedDir = java.io.File(context.cacheDir, "shared_clipboard").apply { mkdirs() }
                                val cacheFile = java.io.File(sharedDir, fileName)
                                cacheFile.writeBytes(update.imageData.toByteArray())
                                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    cacheFile
                                )
                                val mime = if (update.contentType.isNotBlank()) update.contentType else "application/octet-stream"
                                val clip = ClipData(android.content.ClipDescription(fileName, arrayOf(mime)), ClipData.Item(contentUri))
                                clipboardManager.setPrimaryClip(clip)
                                lastReceivedClipboardText = "[$fileName (${update.imageData.size() / 1024} KB)]"
                                lastActionLog = "Received $fileName ready to paste!"
                            } else if (!text.isNullOrBlank()) {
                                val clip = ClipData.newPlainText("Bridge", text)
                                clipboardManager.setPrimaryClip(clip)
                                lastReceivedClipboardText = text
                                lastActionLog = "Received from Mac: ${text.take(30)}..."
                            }
                        } catch (e: Exception) {
                            println("MainActivity: setPrimaryClip error: ${e.message}")
                        }
                    }
                    val notifText = if (hasImage) "Ready to paste: ${update.fileName.ifEmpty { "file" }}" else text
                    BridgeForegroundService.notifyReceivedClipboard(context, notifText)
                }
                if (envelope.hasShareText()) {
                    textSharingService.handleIncomingShare(envelope)
                }
                if (envelope.hasFileRequest()) {
                    val acceptEnv = transferManager.handleIncomingRequest(envelope, localDeviceId, "live-session", autoAccept = true)
                    BridgeConnectionManager.sendEnvelope(acceptEnv)
                }
                if (envelope.hasFileChunk()) {
                    val (progress, _) = transferManager.handleIncomingChunk(envelope)
                    activeTransferProgress = progress
                }
            }

            override fun onDisconnected(reason: String) {
                connectionState = ConnectionState.DISCONNECTED
                activeRouteType = null
                BridgeForegroundService.stopService(context)
                diagnosticLogger.log(DiagnosticCategory.CONNECTION_STATE, "Socket disconnected: $reason")
                lastActionLog = "Disconnected ($reason)"
            }
        }
    }

    DisposableEffect(Unit) {
        BridgeConnectionManager.addListener(socketListener)
        BridgeNotificationListenerService.activeProcessor = notificationProcessor
        BridgeNotificationListenerService.localDeviceId = localDeviceId
        BridgeNotificationListenerService.activeSessionId = "live-session"

        onDispose {
            BridgeConnectionManager.removeListener(socketListener)
        }
    }

    LaunchedEffect(Unit) {
        if (!BridgeConnectionManager.isConnected && targetMacIpInput.isNotBlank()) {
            BridgeConnectionManager.connectToMac(targetMacIpInput.trim(), 54884) { success ->
                if (success) {
                    val hello = Envelope.newBuilder()
                        .setMessageId("hello-auto")
                        .setSessionId("live-session")
                        .setSenderDeviceId(localDeviceId)
                        .setProtocolMajor(1)
                        .setProtocolMinor(0)
                        .build()
                    BridgeConnectionManager.sendEnvelope(hello)
                }
            }
        }
        coroutineScope.launch {
            UpdateManager.checkForUpdate(currentAppVersion).onSuccess { update ->
                availableUpdate = update
                if (update != null) {
                    updateCheckStatus = "Update available: ${update.tagName}"
                }
            }
        }
    }
    // Hook up background notification & auto-sync
    BridgeForegroundService.localDeviceId = localDeviceId
    BridgeForegroundService.onClipboardSyncRequested = { text ->
        BridgeForegroundService.lastSentText = text
        val env = clipboardProcessor.handleLocalCopy(text, localDeviceId, "live-session", forceSend = true)
        if (env != null) {
            BridgeConnectionManager.sendEnvelope(env) { sent ->
                lastActionLog = if (sent) "Sent to Mac: ${text.take(30)}..." else "Cannot send (Mac not connected)"
            }
        }
    }
    // Clipboard listener hook for foreground changes
    DisposableEffect(clipboardEnabled) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (clipboardEnabled) {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    if (!text.isNullOrBlank()) {
                        val env = clipboardProcessor.handleLocalCopy(text, localDeviceId, "live-session", forceSend = false)
                        if (env != null) {
                            BridgeConnectionManager.sendEnvelope(env) { sent ->
                                if (sent) lastActionLog = "Synced clipboard: ${text.take(30)}..."
                            }
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

        val allPermissionsReady = hasNotificationAccess && hasAccessibility && hasBatteryExemption && hasPostNotifications
        var permissionsExpanded by remember { mutableStateOf(!allPermissionsReady) }

        // Setup & Permissions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { permissionsExpanded = !permissionsExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (allPermissionsReady) "🛡️ App Permissions (All Set)" else "⚠️ Permissions Setup Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (allPermissionsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = if (permissionsExpanded) "▲ Hide" else "▼ Manage",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (permissionsExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable the features you want Bridge to provide. Denied features will gracefully stay offline without affecting other capabilities.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    PermissionSetupRow(
                        title = "Notification Mirroring",
                        description = "Mirror Android notifications to Mac and handle quick replies.",
                        isGranted = hasNotificationAccess,
                        actionLabel = "Enable Access",
                        onAction = {
                            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    PermissionSetupRow(
                        title = "Background Clipboard Sync",
                        description = "Sync copied text across all apps to Mac without keeping Bridge in foreground.",
                        isGranted = hasAccessibility,
                        actionLabel = "Enable in Accessibility",
                        onAction = {
                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    PermissionSetupRow(
                        title = "Unrestricted Battery",
                        description = "Prevent Android from putting Bridge to sleep in the background.",
                        isGranted = hasBatteryExemption,
                        actionLabel = "Exempt App",
                        onAction = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val fallback = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(fallback)
                            }
                        }
                    )

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        PermissionSetupRow(
                            title = "Foreground Notifications",
                            description = "Show connection indicator and quick clipboard sync button in notification tray.",
                            isGranted = hasPostNotifications,
                            actionLabel = "Allow",
                            onAction = {
                                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        val updateBanner = availableUpdate
        if (updateBanner != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🚀 New Version Available: ${updateBanner.tagName}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "A newer version of Bridge is available to install (current: v$currentAppVersion).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            UpdateManager.openDownload(context, updateBanner.apkDownloadUrl ?: updateBanner.releaseUrl)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("⬇️ Download & Update to ${updateBanner.tagName}")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

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
                        text = if (BridgeConnectionManager.isConnected) "CONNECTED" else connectionState.name,
                        fontWeight = FontWeight.Bold,
                        color = if (BridgeConnectionManager.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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
                            BridgeConnectionManager.startServer(54884)
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
                            connectionState = ConnectionState.DISCOVERING
                            lastActionLog = "Server listening on 54884"
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
                            BridgeConnectionManager.closeAll()
                            connectionState = ConnectionState.DISCONNECTED
                            BridgeForegroundService.stopService(context)
                            lastActionLog = "Disconnected"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        // Available Macs List & Direct Connect
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Discovered Macs on Network",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                discoveredMacs.forEach { mac ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(mac.displayName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(mac.host, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                targetMacIpInput = mac.host
                                BridgeConnectionManager.connectToMac(mac.host, 54884) { success ->
                                    if (success) {
                                        val hello = Envelope.newBuilder()
                                            .setMessageId("hello-1")
                                            .setSessionId("live-session")
                                            .setSenderDeviceId(localDeviceId)
                                            .build()
                                        BridgeConnectionManager.sendEnvelope(hello)
                                        lastActionLog = "Connected to ${mac.displayName}!"
                                    } else {
                                        lastActionLog = "Connection to ${mac.host} failed."
                                    }
                                }
                            }
                        ) {
                            Text("Connect")
                        }
                    }
                    HorizontalDivider()
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = targetMacIpInput,
                    onValueChange = { targetMacIpInput = it },
                    label = { Text("Manual Mac IP (e.g. 192.168.1.5)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (targetMacIpInput.isNotBlank()) {
                            BridgeConnectionManager.connectToMac(targetMacIpInput.trim(), 54884) { success ->
                                if (success) {
                                    val hello = Envelope.newBuilder()
                                        .setMessageId("hello-1")
                                        .setSessionId("live-session")
                                        .setSenderDeviceId(localDeviceId)
                                        .build()
                                    BridgeConnectionManager.sendEnvelope(hello)
                                    lastActionLog = "Connected to Mac at ${targetMacIpInput.trim()}!"
                                } else {
                                    lastActionLog = "Connection to ${targetMacIpInput.trim()} failed."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect to Manual IP")
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
                                connectionState = ConnectionState.CONNECTED
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
                                connectionState = ConnectionState.DISCONNECTED
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
                text = "No paired devices yet. Tap 'Start Server / Pair' or connect to a Mac above.",
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
                                BridgeConnectionManager.sendEnvelope(env) { sent ->
                                    lastActionLog = if (sent) (if (isUrl) "URL sent to Mac!" else "Text sent to Mac!") else "Failed to send (not connected)"
                                }
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
                            BridgeConnectionManager.sendEnvelope(reqEnv) { sent ->
                                lastActionLog = if (sent) "File transfer started: ${sample.name}" else "Failed to send (not connected)"
                            }
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

                if (clipboardEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val clip = clipboardManager.primaryClip
                            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else null
                            println("BridgeApp: Send Clipboard button clicked. text='$text'")
                            if (!text.isNullOrBlank()) {
                                val env = clipboardProcessor.handleLocalCopy(text, localDeviceId, "live-session", forceSend = true)
                                println("BridgeApp: handleLocalCopy envelope is null? ${env == null}")
                                if (env != null) {
                                    BridgeConnectionManager.sendEnvelope(env) { sent ->
                                        println("BridgeApp: sendEnvelope callback sent=$sent")
                                        lastActionLog = if (sent) "Sent to Mac: ${text.take(30)}..." else "Cannot send (Mac not connected)"
                                    }
                                } else {
                                    lastActionLog = "Clipboard text unchanged or rejected"
                                }
                            } else {
                                lastActionLog = "Clipboard is currently empty"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 Send Current Clipboard to Mac")
                    }

                    val receivedText = lastReceivedClipboardText
                    if (!receivedText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("📥 Received from Mac:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = receivedText,
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        val clip = ClipData.newPlainText("Bridge", receivedText)
                                        clipboardManager.setPrimaryClip(clip)
                                        lastActionLog = "Copied to phone: ${receivedText.take(30)}..."
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("📋 Copy to Phone Clipboard")
                                }
                            }
                        }
                    }

                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Collapsible Advanced Technical Details & Diagnostics Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedDetails = !showAdvancedDetails },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚙️ Advanced Technical Details & Logs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (showAdvancedDetails) "▲ Hide" else "▼ Show",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showAdvancedDetails) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Device & Network Identifiers",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Device ID: $localDeviceId",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Phone IP: " + if (localIps.isNotEmpty()) localIps.joinToString(", ") else "127.0.0.1",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Fingerprint: " + IdentityManager.fingerprint(localPublicKey),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Diagnostic Logs (Privacy-Preserving)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Structured connection state transitions with zero sensitive payload logging.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            exportedBundleJson = diagnosticLogger.exportDiagnosticBundle()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Redacted Diagnostic Bundle")
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

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "System Compatibility & Reliability Checks",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    PermissionAdvisor.getStandardAndroidChecks().forEach { check ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = check.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(
                                    text = check.state.name,
                                    color = if (check.state == PermissionState.GRANTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = check.description,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Application Updates Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Application Updates",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Current installed version: v$currentAppVersion",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        isCheckingUpdate = true
                        updateCheckStatus = "Checking GitHub for updates..."
                        coroutineScope.launch {
                            UpdateManager.checkForUpdate(currentAppVersion).onSuccess { update ->
                                isCheckingUpdate = false
                                availableUpdate = update
                                updateCheckStatus = if (update != null) "New version ${update.tagName} is available!" else "You are on the latest version (v$currentAppVersion) ✓"
                            }.onFailure { err ->
                                isCheckingUpdate = false
                                updateCheckStatus = "Check failed: ${err.message}"
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isCheckingUpdate) "Checking GitHub..." else "🔄 Check for Updates")
                }

                if (updateCheckStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateCheckStatus!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val currentAvailable = availableUpdate
                if (currentAvailable != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            UpdateManager.openDownload(context, currentAvailable.apkDownloadUrl ?: currentAvailable.releaseUrl)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⬇️ Download & Install ${currentAvailable.tagName}")
                    }
                }
            }
        }

        lastActionLog?.let { log ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = log,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionSetupRow(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isGranted) {
            Text(
                "✓ Ready",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        } else {
            Button(
                onClick = onAction,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(actionLabel, fontSize = 11.sp)
            }
        }
    }
}
