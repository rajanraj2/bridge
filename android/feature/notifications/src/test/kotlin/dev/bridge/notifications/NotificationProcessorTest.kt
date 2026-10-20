package dev.bridge.notifications

import dev.bridge.protocol.v1.NotificationAction
import dev.bridge.protocol.v1.NotificationActionDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProcessorTest {

    @Test
    fun testEligibleNotificationForwarding() {
        val processor = NotificationProcessor()
        val envelope = processor.processNotification(
            sourcePackage = "com.google.android.apps.messaging",
            title = "Alice",
            body = "Lunch tomorrow?",
            category = "msg",
            isSensitive = false,
            actions = listOf(
                NotificationActionDescriptor.newBuilder()
                    .setActionId("reply")
                    .setLabel("Reply")
                    .setRequiresTextInput(true)
                    .build()
            ),
            localDeviceId = "android-001",
            sessionId = "sess-100"
        )

        assertNotNull(envelope)
        assertEquals("android-001", envelope?.senderDeviceId)
        assertEquals("sess-100", envelope?.sessionId)
        assertTrue(envelope!!.hasNotificationEvent())

        val event = envelope.notificationEvent
        assertEquals("com.google.android.apps.messaging", event.sourcePackageOrBundleId)
        assertEquals("Alice", event.title)
        assertEquals("Lunch tomorrow?", event.body)
        assertEquals(1, event.actionsCount)
        assertEquals("reply", event.getActions(0).actionId)
        assertEquals(1, processor.forwardedCount)
    }

    @Test
    fun testSensitiveNotificationSuppressedByDefault() {
        val processor = NotificationProcessor(
            filterPolicy = NotificationFilterPolicy(suppressSensitive = true)
        )
        val envelope = processor.processNotification(
            sourcePackage = "com.chase.sig.android",
            title = "Security Alert",
            body = "Your verification code is 123456",
            isSensitive = true,
            localDeviceId = "android-001",
            sessionId = "sess-100"
        )

        assertNull("Sensitive notifications must be suppressed by default", envelope)
        assertEquals(1, processor.filteredCount)
        assertEquals(0, processor.forwardedCount)
    }

    @Test
    fun testBlockedPackageFilter() {
        val processor = NotificationProcessor(
            filterPolicy = NotificationFilterPolicy(
                blockedPackages = setOf("com.spam.annoyingapp")
            )
        )

        val blockedEnvelope = processor.processNotification(
            sourcePackage = "com.spam.annoyingapp",
            title = "Ad",
            body = "Buy now",
            localDeviceId = "android-001",
            sessionId = "sess-100"
        )
        assertNull(blockedEnvelope)
        assertEquals(1, processor.filteredCount)

        val allowedEnvelope = processor.processNotification(
            sourcePackage = "com.slack",
            title = "Work",
            body = "Meeting started",
            localDeviceId = "android-001",
            sessionId = "sess-100"
        )
        assertNotNull(allowedEnvelope)
        assertEquals(1, processor.forwardedCount)
    }

    @Test
    fun testDeduplicationPreventsRepetition() {
        val deduplicator = NotificationDeduplicator()
        assertTrue(deduplicator.recordIfNotSeen("evt-001"))
        assertFalse("Duplicate event ID must be rejected", deduplicator.recordIfNotSeen("evt-001"))
        assertTrue(deduplicator.isDuplicate("evt-001"))
        assertFalse(deduplicator.isDuplicate("evt-002"))
    }

    @Test
    fun testNotificationActionCallback() {
        val processor = NotificationProcessor()
        var receivedTarget: String? = null
        var receivedAction: String? = null
        var receivedText: String? = null

        processor.addActionListener(object : NotificationActionListener {
            override fun onNotificationAction(targetEventId: String, actionId: String, textInput: String?) {
                receivedTarget = targetEventId
                receivedAction = actionId
                receivedText = textInput
            }
        })

        val incomingAction = NotificationAction.newBuilder()
            .setEventId("evt-555")
            .setActionId("reply")
            .setTextInput("See you there!")
            .build()

        processor.handleIncomingAction(incomingAction)

        assertEquals("evt-555", receivedTarget)
        assertEquals("reply", receivedAction)
        assertEquals("See you there!", receivedText)
    }
}
