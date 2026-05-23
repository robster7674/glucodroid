package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

class HistoryRecoveryPolicyTests {

    private val minute = 60_000L
    private val tolerance = DashboardHistoryCollectionPolicy.HISTORY_RECOVERY_TOLERANCE_MS
    private val tailTolerance = DashboardHistoryCollectionPolicy.HISTORY_RECOVERY_TAIL_TOLERANCE_MS

    private fun point(timestamp: Long, serial: String = "A") = GlucosePoint(
        value = 100f,
        time = "",
        timestamp = timestamp,
        rawValue = 95f,
        sensorSerial = serial
    )

    private fun shouldRecover(
        startTimeMs: Long = 0L,
        history: List<GlucosePoint>,
        serial: String? = "A",
        currentTimeMs: Long = 0L,
        sensorMatches: Boolean = true
    ) = DashboardHistoryCollectionPolicy.shouldRequestHistoryRecovery(
        startTimeMs = startTimeMs,
        history = history,
        serial = serial,
        currentTimeMs = currentTimeMs,
        currentSensorMatchesSerial = sensorMatches
    )

    @Test
    fun recoveryRequestedWhenHistoryIsEmpty() {
        assertTrue(shouldRecover(history = emptyList(), currentTimeMs = 30 * minute))
    }

    @Test
    fun recoveryRequestedWhenOldestPointIsAfterStartPlusTolerance() {
        val startTime = 10 * minute
        val tooLate = startTime + tolerance + 1L
        val history = listOf(point(tooLate), point(tooLate + 5 * minute))
        assertTrue(shouldRecover(startTimeMs = startTime, history = history, currentTimeMs = tooLate + 60 * minute))
    }

    @Test
    fun noRecoveryWhenOldestPointIsWithinTolerance() {
        val startTime = 10 * minute
        val withinTolerance = startTime + tolerance - 1L
        val history = listOf(point(withinTolerance), point(withinTolerance + 60 * minute))
        // currentTimeMs = 0 → no current, short-circuits to false after start-time check passes
        assertFalse(shouldRecover(startTimeMs = startTime, history = history, currentTimeMs = 0L))
    }

    @Test
    fun startTimeModeZeroSkipsOldestPointCheck() {
        // startTimeMs = 0 → the "oldest starts too late" guard is inactive
        val history = listOf(point(1000 * minute))
        assertFalse(shouldRecover(startTimeMs = 0L, history = history, currentTimeMs = 0L))
    }

    @Test
    fun noRecoveryWhenCurrentReadingIsAbsent() {
        val history = listOf(point(10 * minute), point(20 * minute))
        assertFalse(shouldRecover(history = history, currentTimeMs = 0L))
    }

    @Test
    fun noRecoveryWhenSerialIsBlank() {
        val history = listOf(point(10 * minute), point(20 * minute))
        assertFalse(shouldRecover(history = history, serial = "", currentTimeMs = 25 * minute))
    }

    @Test
    fun noRecoveryWhenSensorDoesNotMatch() {
        val history = listOf(point(10 * minute), point(20 * minute))
        assertFalse(shouldRecover(history = history, currentTimeMs = 25 * minute, sensorMatches = false))
    }

    @Test
    fun recoveryRequestedWhenCurrentReadingExceedsTailTolerance() {
        val lastHistoryTs = 20 * minute
        val currentTs = lastHistoryTs + tailTolerance + 1L
        val history = listOf(point(10 * minute), point(lastHistoryTs))
        assertTrue(shouldRecover(history = history, currentTimeMs = currentTs))
    }

    @Test
    fun noRecoveryWhenCurrentReadingIsWithinTailTolerance() {
        val lastHistoryTs = 20 * minute
        val currentTs = lastHistoryTs + tailTolerance - 1L
        val history = listOf(point(10 * minute), point(lastHistoryTs))
        assertFalse(shouldRecover(history = history, currentTimeMs = currentTs))
    }

    @Test
    fun noRecoveryWhenCurrentReadingTimestampMatchesLastHistory() {
        val lastHistoryTs = 20 * minute
        val history = listOf(point(10 * minute), point(lastHistoryTs))
        assertFalse(shouldRecover(history = history, currentTimeMs = lastHistoryTs))
    }
}
