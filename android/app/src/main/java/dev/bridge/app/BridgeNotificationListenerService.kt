package dev.bridge.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.bridge.notifications.NotificationProcessor
import dev.bridge.protocol.v1.NotificationActionDescriptor

class BridgeNotificationListenerService : NotificationListenerService() {

    companion object {
        var activeProcessor: NotificationProcessor? = null
        var localDeviceId: String = ""
        var activeSessionId: String = ""
        var isConnected: Boolean = false
        var onNotificationCaptured: ((String, String, String) -> Unit)? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Skip our own notifications to avoid feedback
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val category = notification.category

        val isSensitive = (notification.visibility == Notification.VISIBILITY_SECRET ||
                           notification.visibility == Notification.VISIBILITY_PRIVATE)

        val actionDescriptors = mutableListOf<NotificationActionDescriptor>()
        notification.actions?.forEach { action ->
            val actionId = action.title?.toString() ?: "action"
            val hasRemoteInput = action.remoteInputs != null && action.remoteInputs.isNotEmpty()
            actionDescriptors.add(
                NotificationActionDescriptor.newBuilder()
                    .setActionId(actionId)
                    .setLabel(action.title?.toString() ?: "Action")
                    .setRequiresTextInput(hasRemoteInput)
                    .build()
            )
        }

        onNotificationCaptured?.invoke(sbn.packageName, title, text)

        activeProcessor?.processNotification(
            sourcePackage = sbn.packageName,
            title = title,
            body = text,
            category = category,
            isSensitive = isSensitive,
            actions = actionDescriptors,
            localDeviceId = localDeviceId,
            sessionId = activeSessionId
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }
}
