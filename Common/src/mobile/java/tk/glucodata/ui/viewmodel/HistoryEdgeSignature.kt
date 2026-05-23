package tk.glucodata.ui.viewmodel

import tk.glucodata.ui.GlucosePoint

internal data class HistoryEdgeSignature(
    val size: Int,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val sampleHash: Int,
    val lastValueBits: Int,
    val lastRawBits: Int,
    val lastSerial: String?
)

internal fun historyEdgeSignature(points: List<GlucosePoint>): HistoryEdgeSignature {
    val first = points.firstOrNull()
    val last = points.lastOrNull()
    return HistoryEdgeSignature(
        size = points.size,
        firstTimestamp = first?.timestamp ?: 0L,
        lastTimestamp = last?.timestamp ?: 0L,
        sampleHash = sparseHistorySampleHash(points),
        lastValueBits = java.lang.Float.floatToRawIntBits(last?.value ?: 0f),
        lastRawBits = java.lang.Float.floatToRawIntBits(last?.rawValue ?: 0f),
        lastSerial = last?.sensorSerial
    )
}

internal fun sparseHistorySampleHash(points: List<GlucosePoint>): Int {
    if (points.isEmpty()) return 0
    val sampleCount = minOf(points.size, 8)
    var hash = 1
    for (sampleIndex in 0 until sampleCount) {
        val pointIndex = ((points.lastIndex.toLong() * sampleIndex) / (sampleCount - 1).coerceAtLeast(1)).toInt()
        val point = points[pointIndex]
        hash = 31 * hash + point.timestamp.hashCode()
        hash = 31 * hash + java.lang.Float.floatToRawIntBits(point.value)
        hash = 31 * hash + java.lang.Float.floatToRawIntBits(point.rawValue)
        hash = 31 * hash + (point.sensorSerial?.hashCode() ?: 0)
    }
    return hash
}
