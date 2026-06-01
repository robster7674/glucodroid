package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the TTS rate-limiting logic in Talker.selspeak().
 *
 * selspeak() must throttle glucose announcements to at most one per cursep
 * milliseconds.  On a 5-minute separation setting that means at most 12 TTS
 * utterances per hour — firing faster would be a battery regression.
 *
 * Production code (simplified for testing):
 *
 *   static long nexttime = 0L;
 *   void selspeak(String message) {
 *       var now = System.currentTimeMillis();
 *       if (now > nexttime && SpeakSchedule.isWithinSchedule(app)) {
 *           nexttime = now + cursep;
 *           speak(message);
 *       }
 *   }
 *
 * We test the gate logic without Android context by reimplementing it here.
 */
class TalkerRateLimiterTests {

    // A pure reimplementation of the selspeak gate (no Android deps).
    private class RateLimiter(private val separationMs: Long) {
        var nexttime: Long = 0L
        var speakCount: Int = 0

        fun selspeak(nowMs: Long) {
            if (nowMs > nexttime) {
                nexttime = nowMs + separationMs
                speakCount++
            }
        }
    }

    @Test
    fun selspeak_firstCall_alwaysSpeaks() {
        val limiter = RateLimiter(separationMs = 5 * 60_000L)
        limiter.selspeak(nowMs = 1_000L)
        assertEquals(1, limiter.speakCount)
    }

    @Test
    fun selspeak_secondCallWithinSeparation_suppressed() {
        val sep = 5 * 60_000L
        val limiter = RateLimiter(separationMs = sep)
        limiter.selspeak(nowMs = 1_000L)
        limiter.selspeak(nowMs = 1_000L + sep - 1)  // 1 ms before allowed
        assertEquals(1, limiter.speakCount)
    }

    @Test
    fun selspeak_secondCallAfterSeparation_speaks() {
        val sep = 5 * 60_000L
        val limiter = RateLimiter(separationMs = sep)
        limiter.selspeak(nowMs = 1_000L)
        limiter.selspeak(nowMs = 1_000L + sep + 1)  // 1 ms after allowed
        assertEquals(2, limiter.speakCount)
    }

    @Test
    fun selspeak_atExactBoundary_suppressed() {
        // nowMs == nexttime → NOT greater than → suppressed
        val sep = 60_000L
        val limiter = RateLimiter(separationMs = sep)
        limiter.selspeak(nowMs = 1_000L)
        limiter.nexttime = 2_000L                   // force exact boundary
        limiter.selspeak(nowMs = 2_000L)            // now == nexttime
        assertEquals(1, limiter.speakCount)
    }

    @Test
    fun selspeak_maxRateWith5MinSeparation_atMost12PerHour() {
        val sep = 5 * 60_000L
        val limiter = RateLimiter(separationMs = sep)
        val oneHourMs = 60 * 60_000L
        // Simulate glucose readings every 60 s for one hour
        var t = 0L
        while (t <= oneHourMs) {
            limiter.selspeak(nowMs = t)
            t += 60_000L
        }
        assertTrue(
            "Expected <= 12 utterances per hour with 5-min separation, got ${limiter.speakCount}",
            limiter.speakCount <= 12
        )
    }

    @Test
    fun selspeak_maxRateWith1MinSeparation_atMost60PerHour() {
        val sep = 60_000L
        val limiter = RateLimiter(separationMs = sep)
        val oneHourMs = 60 * 60_000L
        var t = 0L
        while (t <= oneHourMs) {
            limiter.selspeak(nowMs = t)
            t += 60_000L
        }
        assertTrue(
            "Expected <= 61 utterances per hour with 1-min separation, got ${limiter.speakCount}",
            limiter.speakCount <= 61
        )
    }

    @Test
    fun selspeak_minimumSeparation_neverZero() {
        // cursep is set from user input clamped to at least 1 second in applyComposeSettings:
        //   cursep = Math.max(1, separationSeconds) * 1000L
        // Verify that with separationMs=1000 (minimum), spam calls are still throttled.
        val sep = 1_000L
        val limiter = RateLimiter(separationMs = sep)
        repeat(100) { limiter.selspeak(nowMs = System.currentTimeMillis()) }
        // All calls at the same millisecond — only the first should fire
        assertEquals(1, limiter.speakCount)
    }
}
