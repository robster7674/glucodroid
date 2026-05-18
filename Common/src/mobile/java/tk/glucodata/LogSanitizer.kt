package tk.glucodata

object LogSanitizer {

    // Bluetooth MAC address: 6 hex pairs separated by colons
    private val macRegex = Regex("""[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}""")

    // Auth/session tokens: "token=VALUE" (anything non-whitespace after the equals)
    private val tokenRegex = Regex("""(?i)\btoken=[^\s,\)]+""")

    // Hex dumps: 8+ space-separated hex byte pairs (characteristic dumps, auth data, etc.)
    private val hexDumpRegex = Regex("""(?:[0-9A-Fa-f]{2} ){7,}[0-9A-Fa-f]{2}""")

    // Glucose readings: "glucose=NNN" — replace with range label
    private val glucoseRegex = Regex("""(?i)\bglucose=(\d{2,4})\b""")

    // Device / sensor identifiers logged after known keywords
    private val serialRegex = Regex("""(?i)\b(serialnumber|serial|sensorid|sensorId)\s*[=: ]\s*([^\s,\)\]]+)""")

    fun sanitize(raw: String): String {
        var text = raw
        text = tokenRegex.replace(text) { "token=[REDACTED]" }
        text = hexDumpRegex.replace(text) { m ->
            val byteCount = (m.value.length + 1) / 3
            "[HEX_REDACTED:${byteCount}_bytes]"
        }
        text = macRegex.replace(text) { "[MAC:REDACTED]" }
        text = glucoseRegex.replace(text) { m ->
            val mg = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val range = when {
                mg < 70 -> "LOW"
                mg > 180 -> "HIGH"
                else -> "IN_RANGE"
            }
            "glucose=$range"
        }
        text = serialRegex.replace(text) { m ->
            val keyword = m.groupValues[1]
            val value = m.groupValues[2]
            val hash = (value.hashCode() and 0xFFFFFF).toString(16).padStart(6, '0')
            "$keyword=[ID:$hash]"
        }
        return text
    }
}
