package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardChartPipelineTests {

    private fun point(timestamp: Long) = GlucosePoint(
        value = 100f,
        time = "",
        timestamp = timestamp,
        rawValue = 95f
    )

    // --- buildSmoothedConsumerHistory: 512-point cap ---

    @Test
    fun consumerHistoryCapsAt512WhenSmoothingDisabled() {
        val points = (1..600).map { i -> point(i * 60_000L) }
        val result = buildSmoothedConsumerHistory(
            points = points,
            smoothingMinutes = 0,
            smoothOnlyGraph = false,
            collapseChunks = false
        )
        assertEquals(DASHBOARD_CONSUMER_HISTORY_MAX_POINTS, result.size)
        // takeLast(512) from 600 → first kept point is at index 88 → timestamp 89*60_000
        assertEquals(89 * 60_000L, result.first().timestamp)
    }

    @Test
    fun consumerHistoryCapsAt512WhenSmoothOnlyGraph() {
        val points = (1..700).map { i -> point(i * 60_000L) }
        val result = buildSmoothedConsumerHistory(
            points = points,
            smoothingMinutes = 5,
            smoothOnlyGraph = true,
            collapseChunks = false
        )
        assertEquals(DASHBOARD_CONSUMER_HISTORY_MAX_POINTS, result.size)
    }

    @Test
    fun consumerHistoryPassesThroughShortList() {
        val points = (1..50).map { i -> point(i * 60_000L) }
        val result = buildSmoothedConsumerHistory(
            points = points,
            smoothingMinutes = 0,
            smoothOnlyGraph = false,
            collapseChunks = false
        )
        assertEquals(50, result.size)
    }

    @Test
    fun consumerHistoryPassesThroughExactlyAtCap() {
        val points = (1..DASHBOARD_CONSUMER_HISTORY_MAX_POINTS).map { i -> point(i * 60_000L) }
        val result = buildSmoothedConsumerHistory(
            points = points,
            smoothingMinutes = 0,
            smoothOnlyGraph = false,
            collapseChunks = false
        )
        assertEquals(DASHBOARD_CONSUMER_HISTORY_MAX_POINTS, result.size)
    }

    @Test
    fun consumerHistoryReturnsEmptyForEmptyInput() {
        val result = buildSmoothedConsumerHistory(
            points = emptyList(),
            smoothingMinutes = 0,
            smoothOnlyGraph = false,
            collapseChunks = false
        )
        assertEquals(0, result.size)
    }

    // --- trimHistoryForPrediction: window + max-points cap ---

    @Test
    fun predictionTrimNeverExceedsMaxPoints() {
        val points = (1..200).map { i -> point(i * 60_000L) }
        val result = trimHistoryForPrediction(points)
        assertTrue(result.size <= PREDICTION_HISTORY_MAX_POINTS)
    }

    @Test
    fun predictionTrimPassesThroughShortList() {
        val points = (1..10).map { i -> point(i * 60_000L) }
        val result = trimHistoryForPrediction(points)
        assertEquals(10, result.size)
    }

    @Test
    fun predictionTrimKeepsPointsWithinOneHourWindow() {
        val nowMs = 100 * 60_000L
        val windowStart = nowMs - 60 * 60_000L  // 1-hour window
        val points = (1..200).map { i -> point(i * 60_000L) }
        val result = trimHistoryForPrediction(points)
        // All returned points should be within or near the 1-hour window of the latest point
        val latestInResult = result.maxOfOrNull { it.timestamp } ?: 0L
        val earliestInResult = result.minOfOrNull { it.timestamp } ?: 0L
        assertTrue(latestInResult - earliestInResult <= 60 * 60_000L + 60_000L)
    }
}
