package dev.bridge.network

import dev.bridge.network.diagnostics.DiagnosticCategory
import dev.bridge.network.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {

    @Test
    fun testSensitiveContentRedaction() {
        val logger = DiagnosticLogger()
        logger.log(
            category = DiagnosticCategory.CONNECTION_STATE,
            message = "Device state transition",
            details = mapOf(
                "peerId" to "dev-123",
                "clipboardText" to "Top Secret Password",
                "notificationTitle" to "Bank OTP 5839",
                "routeType" to "LOCAL_LAN",
                "secretKey" to "raw_private_key_bytes"
            )
        )

        val events = logger.getEvents()
        assertEquals(1, events.size)

        val details = events[0].details
        assertEquals("dev-123", details["peerId"])
        assertEquals("LOCAL_LAN", details["routeType"])

        // Sensitive fields must be redacted!
        assertEquals("[REDACTED]", details["clipboardText"])
        assertEquals("[REDACTED]", details["notificationTitle"])
        assertEquals("[REDACTED]", details["secretKey"])
    }

    @Test
    fun testExportDiagnosticBundle() {
        val logger = DiagnosticLogger()
        logger.log(DiagnosticCategory.HEARTBEAT, "Heartbeat exchanged", mapOf("latencyMs" to "12"))
        val json = logger.exportDiagnosticBundle()

        assertTrue(json.contains("\"bridgeAppVersion\": \"0.1.0\""))
        assertTrue(json.contains("\"os\": \"Android\""))
        assertTrue(json.contains("\"HEARTBEAT\""))
        assertFalse("Logs must never contain unredacted secrets", json.contains("password"))
    }
}
