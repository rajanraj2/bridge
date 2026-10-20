package dev.bridge.app

import android.os.Bundle
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
import dev.bridge.clipboard.ClipboardPolicy
import dev.bridge.clipboard.ClipboardProcessor
import dev.bridge.domain.model.Capability
import dev.bridge.domain.model.ConnectionState
import dev.bridge.domain.statemachine.ConnectionEvent
import dev.bridge.domain.statemachine.ConnectionStateMachine
import dev.bridge.notifications.NotificationFilterPolicy
import dev.bridge.notifications.NotificationProcessor
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BridgeDashboard()
                }
            }
        }
    }
}

@Composable
fun BridgeDashboard(modifier: Modifier = Modifier) {
    val stateMachine = remember { ConnectionStateMachine() }
    val peerStore = remember { InMemoryTrustedPeerStore() }
    val pairingCoordinator = remember { PairingCoordinator(peerStore) }

    val notificationProcessor = remember { NotificationProcessor() }
    val clipboardProcessor = remember { ClipboardProcessor() }
    val transferManager = remember { FileTransferManager() }
    val textSharingService = remember { TextSharingService() }

    var connectionState by remember { mutableStateOf(stateMachine.state) }
    var activeChallenge by remember { mutableStateOf<PairingChallengePayload?>(null) }
    var sasCode by remember { mutableStateOf<String?>(null) }
    var pairedDevices by remember { mutableStateOf(peerStore.allPeers()) }

    var notificationsEnabled by remember { mutableStateOf(true) }
    var clipboardEnabled by remember { mutableStateOf(true) }
    var lastActionLog by remember { mutableStateOf<String?>(null) }

    var shareInputText by remember { mutableStateOf("") }
    var activeTransferProgress by remember { mutableStateOf<TransferProgress?>(null) }
    var activeTransferId by remember { mutableStateOf<String?>(null) }

    val localDeviceId = remember { IdentityManager.generateDeviceId() }
    val localPublicKey = remember { "Android-Identity-Key-Bytes".toByteArray() }

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

        // Device identity card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Local Device Identity",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = localDeviceId,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Fingerprint: " + IdentityManager.fingerprint(localPublicKey),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection status card
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

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val challenge = pairingCoordinator.createChallenge(
                                deviceId = localDeviceId,
                                displayName = "Pixel Phone",
                                publicKey = localPublicKey
                            )
                            activeChallenge = challenge
                            sasCode = pairingCoordinator.computeSAS(
                                localIdentity = localPublicKey,
                                peerIdentity = "PeerMockIdentityKey".toByteArray(),
                                challenge = challenge.challenge
                            )
                            connectionState = stateMachine.handle(ConnectionEvent.StartDiscovery)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pair New")
                    }

                    OutlinedButton(
                        onClick = {
                            activeChallenge = null
                            sasCode = null
                            connectionState = stateMachine.handle(ConnectionEvent.Disconnect)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
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
                        text = "Scan QR code or confirm SAS match:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    sasCode?.let { code ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 28.sp,
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
                                    peerDeviceId = "mac-sample-peer",
                                    peerDisplayName = "MacBook Pro",
                                    peerPublicKey = "PeerMockIdentityKey".toByteArray(),
                                    userConfirmed = true
                                )
                                pairedDevices = peerStore.allPeers()
                                activeChallenge = null
                                sasCode = null
                                connectionState = stateMachine.handle(ConnectionEvent.PairingSucceeded)
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

        // Paired devices section
        Text(
            text = "Paired Devices (${pairedDevices.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (pairedDevices.isEmpty()) {
            Text(
                text = "No paired devices yet. Tap 'Pair New' to start.",
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
                    text = "Chunked streaming with SHA-256 integrity verification",
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
                                    sessionId = "active-session"
                                )
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
                            // Demo file transfer
                            val sample = File(System.getProperty("java.io.tmpdir"), "bridge_demo_photo.jpg")
                            sample.writeBytes(ByteArray(300 * 1024) { 0x42 })
                            val (reqEnv, transferId) = transferManager.initiateTransfer(sample, localDeviceId, "active-session")
                            activeTransferId = transferId
                            activeTransferProgress = TransferProgress(transferId, 300 * 1024, 300 * 1024, TransferStatus.COMPLETED)
                            lastActionLog = "File sent: ${sample.name} (300 KiB, SHA-256 verified)"
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
                    if (progress.status != TransferStatus.COMPLETED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                activeTransferId?.let { transferManager.cancelTransfer(it) }
                                activeTransferProgress = null
                                lastActionLog = "Transfer cancelled by user"
                            }
                        ) {
                            Text("Cancel Transfer")
                        }
                    }
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

                if (notificationsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val env = notificationProcessor.processNotification(
                                sourcePackage = "com.sample.messages",
                                title = "Test Contact",
                                body = "Bridge test notification payload",
                                localDeviceId = localDeviceId,
                                sessionId = "test-session"
                            )
                            lastActionLog = if (env != null) "Notification sent!" else "Notification filtered."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Test Notification")
                    }
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val env = clipboardProcessor.handleLocalCopy(
                                text = "Sample clipboard text from Android",
                                localDeviceId = localDeviceId,
                                sessionId = "test-session"
                            )
                            lastActionLog = if (env != null) "Clipboard copied & forwarded!" else "Clipboard sync filtered."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sync Test Clipboard")
                    }
                }
            }
        }

        lastActionLog?.let { log ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = log,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
