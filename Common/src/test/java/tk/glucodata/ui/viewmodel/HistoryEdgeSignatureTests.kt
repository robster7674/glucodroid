package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

class HistoryEdgeSignatureTests {

    private fun point(
        timestamp: Long,
        value: Float = 100f,
        rawValue: Float = 95f,
        serial: String? = "A"
    ) = GlucosePoint(value = value, time = "", timestamp = timestamp, rawValue = rawValue, sensorSerial = serial)

    @Test
    fun emptyListProducesZeroSignature() {
        val sig = historyEdgeSignature(emptyList())
        assertEquals(0, sig.size)
        assertEquals(0L, sig.firstTimestamp)
        assertEquals(0L, sig.lastTimestamp)
        assertEquals(0, sig.sampleHash)
        assertNull(sig.lastSerial)
    }

    @Test
    fun identicalListsProduceSameSignature() {
        val a = listOf(point(1000L), point(2000L), point(3000L))
        val b = listOf(point(1000L), point(2000L), point(3000L))
        assertEquals(historyEdgeSignature(a), historyEdgeSignature(b))
    }

    @Test
    fun appendingPointChangesSignature() {
        val before = listOf(point(1000L), point(2000L))
        val after = listOf(point(1000L), point(2000L), point(3000L))
        assertNotEquals(historyEdgeSignature(before), historyEdgeSignature(after))
    }

    @Test
    fun changingLastValueChangesSignature() {
        val before = listOf(point(1000L, value = 100f), point(2000L, value = 110f))
        val after = listOf(point(1000L, value = 100f), point(2000L, value = 111f))
        assertNotEquals(historyEdgeSignature(before), historyEdgeSignature(after))
    }

    @Test
    fun changingLastRawValueChangesSignature() {
        val before = listOf(point(1000L, rawValue = 90f), point(2000L, rawValue = 95f))
        val after = listOf(point(1000L, rawValue = 90f), point(2000L, rawValue = 96f))
        assertNotEquals(historyEdgeSignature(before), historyEdgeSignature(after))
    }

    @Test
    fun changingLastSerialChangesSignature() {
        val before = listOf(point(1000L, serial = "A"), point(2000L, serial = "A"))
        val after = listOf(point(1000L, serial = "A"), point(2000L, serial = "B"))
        assertNotEquals(historyEdgeSignature(before), historyEdgeSignature(after))
    }

    @Test
    fun singlePointSignatureReflectsThatPoint() {
        val p = point(5000L, value = 80f, rawValue = 70f, serial = "SEN1")
        val sig = historyEdgeSignature(listOf(p))
        assertEquals(1, sig.size)
        assertEquals(5000L, sig.firstTimestamp)
        assertEquals(5000L, sig.lastTimestamp)
        assertEquals("SEN1", sig.lastSerial)
        assertEquals(java.lang.Float.floatToRawIntBits(80f), sig.lastValueBits)
        assertEquals(java.lang.Float.floatToRawIntBits(70f), sig.lastRawBits)
    }

    @Test
    fun sparseHashCoversMidListPointsForLargeList() {
        // 20-point list: sparse sampler hits interior points, so changing one mid-list
        // value must change the hash (it would be a false-negative if not caught)
        val base = (1..20).map { i -> point(timestamp = i * 1000L, value = 100f) }
        val modified = base.toMutableList()
        modified[10] = point(timestamp = 11 * 1000L, value = 200f)
        assertNotEquals(sparseHistorySampleHash(base), sparseHistorySampleHash(modified.toList()))
    }

    @Test
    fun sparseHashIsStableForSingleElement() {
        val points = listOf(point(1000L, value = 80f))
        assertEquals(sparseHistorySampleHash(points), sparseHistorySampleHash(points))
    }

    @Test
    fun emptyListHashIsZero() {
        assertEquals(0, sparseHistorySampleHash(emptyList()))
    }
}
