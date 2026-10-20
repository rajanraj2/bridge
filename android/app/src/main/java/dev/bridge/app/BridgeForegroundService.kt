package dev.bridge.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.protobuf.ByteString
import dev.bridge.network.socket.BridgeConnectionManager
import dev.bridge.protocol.v1.ClipboardUpdate
import dev.bridge.protocol.v1.Envelope
import dev.bridge.security.IdentityManager
import java.util.UUID
class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "bridge_connection_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "dev.bridge.app.action.START"
        const val ACTION_STOP = "dev.bridge.app.action.STOP"
        const val ACTION_SYNC_CLIPBOARD = "dev.bridge.app.action.SYNC_CLIPBOARD"
        const val ACTION_APPLY_CLIPBOARD = "dev.bridge.app.action.APPLY_CLIPBOARD"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_CLIPBOARD_TEXT = "extra_clipboard_text"

        var onClipboardSyncRequested: ((String) -> Unit)? = null
        var isServiceRunning: Boolean = false
        var lastReceivedText: String? = null
        var localDeviceId: String = "android-device"
        var lastSentText: String? = null

        fun dispatchLocalCopy(text: String) {
            if (text.isBlank() || text == lastReceivedText || text == lastSentText) {
                return
            }
            lastSentText = text
            println("BridgeForegroundService: dispatchLocalCopy: '$text'")
            if (onClipboardSyncRequested != null) {
                onClipboardSyncRequested?.invoke(text)
            } else {
                try {
                    val shaBytes = IdentityManager.sha256(text.toByteArray(Charsets.UTF_8))
                    val update = ClipboardUpdate.newBuilder()
                        .setEventId("clip-" + UUID.randomUUID().toString())
                        .setOriginDeviceId(localDeviceId)
                        .setOriginEventId("bg-" + System.currentTimeMillis())
                        .setContentType("text/plain")
                        .setText(text)
                        .setContentSha256(ByteString.copyFrom(shaBytes))
                        .setCreatedAtUnixMs(System.currentTimeMillis())
                        .build()

                    val env = Envelope.newBuilder()
                        .setMessageId(UUID.randomUUID().toString())
                        .setSessionId("live-session")
                        .setSenderDeviceId(localDeviceId)
                        .setProtocolMajor(1)
                        .setProtocolMinor(0)
                        .setCreatedAtUnixMs(System.currentTimeMillis())
                        .setClipboardUpdate(update)
                        .build()

                    BridgeConnectionManager.sendEnvelope(env) { sent ->
                        println("BridgeForegroundService: direct sendEnvelope sent=$sent")
                    }
                } catch (e: Exception) {
                    println("BridgeForegroundService: direct send error: ${e.message}")
                }
            }
        }
        fun startService(context: Context, peerName: String) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun notifyReceivedClipboard(context: Context, text: String) {
            lastReceivedText = text
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_APPLY_CLIPBOARD
                putExtra(EXTRA_CLIPBOARD_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var currentPeerName: String = "Mac"
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = getSharedPreferences("bridge_identity", Context.MODE_PRIVATE)
        localDeviceId = prefs.getString("device_id", "android-phone") ?: "android-phone"
        setupClipboardListener()
        startLogcatWatcher()
    }
    private fun setupClipboardListener() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank() && text != lastReceivedText && text != lastSentText) {
                        println("BridgeForegroundService: onPrimaryClipChanged detected text='$text'")
                        dispatchLocalCopy(text)
                    }
                } else {
                    println("BridgeForegroundService: primaryClip is null or background restricted, launching trampoline...")
                    val intent = Intent(this, ClipboardSyncActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                println("BridgeForegroundService: clip listener exception: ${e.message}")
                try {
                    val intent = Intent(this, ClipboardSyncActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
        cm.addPrimaryClipChangedListener(clipboardListener)
    }
    private var isLogcatRunning = false

    private fun startLogcatWatcher() {
        if (isLogcatRunning) return
        isLogcatRunning = true

        Thread {
            try {
                val timeStamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-T", timeStamp, "ClipboardService:E", "ClipboardOverlay:V", "*:S"))
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.contains("ClipboardService") || l.contains("ClipboardOverlay")) {
                        println("BridgeForegroundService: logcat detected copy event: $l")
                        val intent = Intent(this, ClipboardSyncActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                println("BridgeForegroundService: logcat watcher stopped: ${e.message}")
            } finally {
                isLogcatRunning = false
            }
        }.apply {
            isDaemon = true
            name = "Bridge-LogcatClipboardWatcher"
            start()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isServiceRunning = true
                currentPeerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Mac"
                startLogcatWatcher()
                val notification = buildForegroundNotification(currentPeerName)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            ACTION_SYNC_CLIPBOARD -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank()) {
                        dispatchLocalCopy(text)
                    }
                }
            }
            ACTION_APPLY_CLIPBOARD -> {
                val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT) ?: lastReceivedText
                if (!text.isNullOrBlank()) {
                    lastReceivedText = text
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Bridge", text)
                        cm?.setPrimaryClip(clip)
                    } catch (e: Exception) {
                        println("BridgeForegroundService: setPrimaryClip error: ${e.message}")
                    }
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildForegroundNotification(currentPeerName))
            }
            ACTION_STOP -> {
                isServiceRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bridge Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains persistent continuity session with paired Mac"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(peerName: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action button to push copied text to Mac directly from notification shade
        val syncIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_SYNC_CLIPBOARD
        }
        val syncPendingIntent = PendingIntent.getService(
            this,
            1,
            syncIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (!lastReceivedText.isNullOrBlank()) {
            builder.setContentTitle("Bridge: Copied on Mac")
                .setContentText(lastReceivedText!!.take(60))

            val copyIntent = Intent(this, BridgeForegroundService::class.java).apply {
                action = ACTION_APPLY_CLIPBOARD
                putExtra(EXTRA_CLIPBOARD_TEXT, lastReceivedText)
            }
            val copyPendingIntent = PendingIntent.getService(
                this,
                2,
                copyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_edit, "📋 Copy to Clipboard", copyPendingIntent)
        } else {
            builder.setContentTitle("Bridge Connected")
                .setContentText("Connected to $peerName")
        }

        builder.addAction(android.R.drawable.ic_menu_send, "📤 Send Phone Clipboard", syncPendingIntent)
        return builder.build()
    }
}
