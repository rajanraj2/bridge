package dev.bridge.domain

import dev.bridge.domain.model.TelemetryManager
import org.junit.Assert.*
import org.junit.Test

class TelemetryManagerTest {
    class InMemoryPreferencesAdapter : TelemetryManager.TelemetryPreferencesAdapter {
        private val map = mutableMapOf<String, Any>()

        override fun getString(key: String): String? = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    @Test
    fun testTelemetryInstallIdPersistenceAndLaunchCount() {
        val adapter = InMemoryPreferencesAdapter()

        val manager1 = TelemetryManager(adapter)
        val initialId = manager1.installId
        assertTrue(initialId.isNotBlank())
        assertTrue(manager1.isEnabled)
        assertEquals(0, manager1.launchCount)

        manager1.recordLaunch("0.1.14")
        assertEquals(1, manager1.launchCount)

        val manager2 = TelemetryManager(adapter)
        assertEquals(initialId, manager2.installId)
        assertEquals(1, manager2.launchCount)

        manager2.isEnabled = false
        manager2.recordLaunch("0.1.14")
        assertEquals(1, manager2.launchCount)

        val snapshot = manager2.getMetricsSnapshot()
        assertEquals(initialId, snapshot["install_id"])
        assertEquals(false, snapshot["telemetry_enabled"])
    }
}
