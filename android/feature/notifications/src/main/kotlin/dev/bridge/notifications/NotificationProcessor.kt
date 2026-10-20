package dev.bridge.notifications

import dev.bridge.protocol.v1.Envelope
import dev.bridge.protocol.v1.NotificationAction
import dev.bridge.protocol.v1.NotificationActionDescriptor
import dev.bridge.protocol.v1.NotificationEvent
import java.util.UUID

interface NotificationForwarder {
    fun sendEnvelope(envelope: Envelope)
}

interface NotificationActionListener {
    fun onNotificationAction(targetEventId: String, actionId: String, textInput: String?)
}

class NotificationProcessor(
    var filterPolicy: NotificationFilterPolicy = NotificationFilterPolicy(),
    private val deduplicator: NotificationDeduplicator = NotificationDeduplicator(),
    private val forwarder: NotificationForwarder? = null
) {
    companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_BODY_LENGTH = 1000
    }

    var totalCapturedCount: Int = 0
        private set
    var forwardedCount: Int = 0
        private set
    var filteredCount: Int = 0
        private set
    var duplicateCount: Int = 0
        private set

    private val actionListeners = mutableListOf<NotificationActionListener>()

    fun addActionListener(listener: NotificationActionListener) {
        actionListeners.add(listener)
    }

    /**
     * Processes an incoming system notification from Android NotificationListenerService.
     * Returns the created Envelope if forwarded, or null if filtered/duplicate.
     * Guaranteed zero content logging.
     */
    fun processNotification(
        sourcePackage: String,
        title: String?,
        body: String?,
        category: String? = null,
        isSensitive: Boolean = false,
        actions: List<NotificationActionDescriptor> = emptyList(),
        localDeviceId: String,
        sessionId: String
    ): Envelope? {
        totalCapturedCount++

        if (!filterPolicy.shouldAllow(sourcePackage, isSensitive)) {
            filteredCount++
            return null
        }

        val eventId = "notif-" + UUID.randomUUID().toString().lowercase()
        if (!deduplicator.recordIfNotSeen(eventId)) {
            duplicateCount++
            return null
        }

        val sanitizedTitle = (title ?: "").take(MAX_TITLE_LENGTH)
        val sanitizedBody = (body ?: "").take(MAX_BODY_LENGTH)

        val notifBuilder = NotificationEvent.newBuilder()
            .setEventId(eventId)
            .setOriginDeviceId(localDeviceId)
            .setSourcePackageOrBundleId(sourcePackage)
            .setTitle(sanitizedTitle)
            .setBody(sanitizedBody)
            .setCategory(category ?: "")
            .setPostedAtUnixMs(System.currentTimeMillis())
            .setIsSensitive(isSensitive)
            .addAllActions(actions)

        val envelope = Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(localDeviceId)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(1)
            .setProtocolMinor(0)
            .setNotificationEvent(notifBuilder)
            .build()

        forwardedCount++
        forwarder?.sendEnvelope(envelope)
        return envelope
    }

    /**
     * Handles an incoming NotificationAction from the remote client.
     */
    fun handleIncomingAction(action: NotificationAction) {
        actionListeners.forEach {
            it.onNotificationAction(action.eventId, action.actionId, action.textInput.ifEmpty { null })
        }
    }
}
