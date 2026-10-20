package dev.bridge.domain.model

import java.util.UUID

public class TelemetryManager(
    private val preferencesAdapter: TelemetryPreferencesAdapter
) {
    interface TelemetryPreferencesAdapter {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun getLong(key: String, defValue: Long): Long
        fun putLong(key: String, value: Long)
        fun getInt(key: String, defValue: Int): Int
        fun putInt(key: String, value: Int)
        fun getBoolean(key: String, defValue: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
    }

    private val installIdKey = "dev.bridge.telemetry.install_id"
    private val installTimestampKey = "dev.bridge.telemetry.install_timestamp"
    private val telemetryEnabledKey = "dev.bridge.telemetry.enabled"
    private val launchCountKey = "dev.bridge.telemetry.launch_count"

    val installId: String
    val installTimestamp: Long
    var isEnabled: Boolean
        get() = preferencesAdapter.getBoolean(telemetryEnabledKey, true)
        set(value) = preferencesAdapter.putBoolean(telemetryEnabledKey, value)

    var launchCount: Int
        private set

    init {
        val existingId = preferencesAdapter.getString(installIdKey)
        if (!existingId.isNullOrBlank()) {
            installId = existingId
        } else {
            val newId = UUID.randomUUID().toString()
            preferencesAdapter.putString(installIdKey, newId)
            installId = newId
        }

        val existingTs = preferencesAdapter.getLong(installTimestampKey, 0L)
        if (existingTs > 0L) {
            installTimestamp = existingTs
        } else {
            val newTs = System.currentTimeMillis()
            preferencesAdapter.putLong(installTimestampKey, newTs)
            installTimestamp = newTs
        }

        launchCount = preferencesAdapter.getInt(launchCountKey, 0)
    }

    fun recordLaunch(version: String, platform: String = "Android") {
        if (!isEnabled) return
        launchCount += 1
        preferencesAdapter.putInt(launchCountKey, launchCount)
    }

    fun getMetricsSnapshot(): Map<String, Any> {
        return mapOf(
            "install_id" to installId,
            "install_timestamp" to installTimestamp,
            "launch_count" to launchCount,
            "telemetry_enabled" to isEnabled,
            "platform" to "Android"
        )
    }
}
