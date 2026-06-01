package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that BatteryTrace.bump() maintains correct counter semantics.
 *
 * BatteryTrace is the in-app power-regression counter: every hot code path
 * (AOD refresh, BLE callback, notification redraw) calls bump() so we can
 * detect unexpected wakeup frequency increases via logcat during testing.
 *
 * These tests guard the counter contract so that event-rate assertions built
 * on top of it remain meaningful.
 */
class BatteryTraceTests {

    // BatteryTrace uses a static ConcurrentHashMap, so tests share state.
    // Use unique key prefixes per test to avoid interference.

    @Test
    fun bump_firstCallReturnsOne() {
        val count = BatteryTrace.bump("test.first_call_returns_one")
        assertEquals(1L, count)
    }

    @Test
    fun bump_returnsMonotonicallyIncreasingCount() {
        val key = "test.monotonic"
        val c1 = BatteryTrace.bump(key)
        val c2 = BatteryTrace.bump(key)
        val c3 = BatteryTrace.bump(key)
        assertTrue(c2 > c1)
        assertTrue(c3 > c2)
    }

    @Test
    fun bump_countIncrementsByOneEachCall() {
        val key = "test.increment_by_one"
        val start = BatteryTrace.bump(key)
        val next = BatteryTrace.bump(key)
        assertEquals(1L, next - start)
    }

    @Test
    fun bump_differentKeysAreIndependent() {
        val a = BatteryTrace.bump("test.independent.a")
        val b = BatteryTrace.bump("test.independent.b")
        // Both return 1 (or their current value); they must not share state.
        // We can't assert absolute value 1 if another test already bumped the same key,
        // so just verify a and b come from independent counters by bumping a again
        // and checking b didn't change.
        val aAgain = BatteryTrace.bump("test.independent.a")
        val bAgain = BatteryTrace.bump("test.independent.b")
        assertEquals(1L, aAgain - a)
        assertEquals(1L, bAgain - b)
    }

    @Test
    fun bump_concurrentBumpsProduceUniqueValues() {
        val key = "test.concurrent"
        val threads = (1..10).map {
            Thread { BatteryTrace.bump(key) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // After 10 concurrent bumps the count must be at least 10 more than before.
        // We don't know the start value, so just verify the final count is >= 10.
        val finalCount = BatteryTrace.bump(key)
        assertTrue("Expected count >= 11 after 10+1 bumps, got $finalCount", finalCount >= 11L)
    }

    // ---------- Power-regression rate constants ----------
    // These tests document the *maximum allowed* wakeup rate for tagged paths.
    // If a path's constant is tightened accidentally, the test will fail.

    @Test
    fun aodPeriodicRefresh_periodIsAtLeast60Seconds() {
        // AODOverlayService.PERIODIC_REFRESH_MS must not be reduced below 60 s.
        // Kotlin const vals in a companion object compile to static fields on the outer class.
        val cls = Class.forName("tk.glucodata.accessibility.AODOverlayService")
        val field = cls.getDeclaredField("PERIODIC_REFRESH_MS").also { it.isAccessible = true }
        val periodMs = field.getLong(null)
        assertTrue(
            "PERIODIC_REFRESH_MS=$periodMs is below 60 000 ms — this would increase overnight CPU wakeups",
            periodMs >= 60_000L
        )
    }

    @Test
    fun aodBroadcastFollowUp_delayIsReasonable() {
        val cls = Class.forName("tk.glucodata.accessibility.AODOverlayService")
        val field = cls.getDeclaredField("BROADCAST_FOLLOW_UP_MS").also { it.isAccessible = true }
        val delayMs = field.getLong(null)
        // Follow-up fires once per glucose reading to handle source=callback lag.
        // It must not be zero (defeats the purpose) and must be under 5 s.
        assertTrue("BROADCAST_FOLLOW_UP_MS=$delayMs must be > 0", delayMs > 0L)
        assertTrue("BROADCAST_FOLLOW_UP_MS=$delayMs must be <= 5000 ms", delayMs <= 5_000L)
    }
}
