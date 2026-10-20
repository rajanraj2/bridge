package dev.bridge.network.diagnostics

enum class DiagnosticCategory {
    CONNECTION_STATE,
    HANDSHAKE,
    HEARTBEAT,
    ROUTE_SELECTION,
    NETWORK_SWITCH,
    ERROR,
    TRANSFER_METRIC
}

data class DiagnosticEvent(
    val timestampUnixMs: Long = System.currentTimeMillis(),
    val category: DiagnosticCategory,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

class DiagnosticLogger {
    companion object {
        val shared = DiagnosticLogger()
        const val MAX_EVENTS = 500
    }

    private val events = mutableListOf<DiagnosticEvent>()

    @Synchronized
    fun log(category: DiagnosticCategory, message: String, details: Map<String, String> = emptyMap()) {
        val cleanDetails = mutableMapOf<String, String>()
        for ((key, value) in details) {
            val lower = key.lowercase()
            if (lower.contains("text") || lower.contains("title") || lower.contains("body") ||
                lower.contains("secret") || lower.contains("key") || lower.contains("token") || lower.contains("payload")) {
                cleanDetails[key] = "[REDACTED]"
            } else {
                cleanDetails[key] = value
            }
        }

        events.add(DiagnosticEvent(category = category, message = message, details = cleanDetails))
        if (events.size > MAX_EVENTS) {
            events.removeAt(0)
        }
    }

    @Synchronized
    fun getEvents(): List<DiagnosticEvent> {
        return events.toList()
    }

    @Synchronized
    fun exportDiagnosticBundle(
        appVersion: String = "0.1.0",
        osVersion: String = "Android"
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"bridgeAppVersion\": \"$appVersion\",\n")
        sb.append("  \"os\": \"Android\",\n")
        sb.append("  \"osVersion\": \"$osVersion\",\n")
        sb.append("  \"exportTimestamp\": ${System.currentTimeMillis()},\n")
        sb.append("  \"eventCount\": ${events.size},\n")
        sb.append("  \"events\": [\n")
        events.forEachIndexed { index, event ->
            sb.append("    {\n")
            sb.append("      \"time\": ${event.timestampUnixMs},\n")
            sb.append("      \"category\": \"${event.category.name}\",\n")
            sb.append("      \"message\": \"${event.message.replace("\"", "\\\"")}\",\n")
            sb.append("      \"details\": {")
            val detailEntries = event.details.entries.joinToString(", ") { "\"${it.key}\": \"${it.value.replace("\"", "\\\"")}\"" }
            sb.append(detailEntries)
            sb.append("}\n")
            sb.append("    }${if (index < events.size - 1) "," else ""}\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }
}
