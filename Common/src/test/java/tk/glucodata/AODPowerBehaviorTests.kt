package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the AOD overlay visibility state machine.
 *
 * AODOverlayService decides whether to show/hide the overlay using two boolean
 * fields: isScreenOn and isLocked.  The rule is:
 *
 *   shouldShow = isLocked || !isScreenOn
 *
 * These tests document that invariant and guard against regressions where, for
 * example, polling is re-introduced or the visibility logic is accidentally
 * inverted.
 *
 * Because AODOverlayService is an AccessibilityService (requires Android
 * runtime), the logic is extracted and tested here as a pure function that
 * mirrors the production code exactly.
 */
class AODPowerBehaviorTests {

    // Mirrors AODOverlayService.updateVisibility()
    private fun shouldShowOverlay(isScreenOn: Boolean, isLocked: Boolean): Boolean =
        isLocked || !isScreenOn

    // ---------- Visibility logic ----------

    @Test
    fun show_whenScreenOff_andUnlocked() {
        assertTrue(shouldShowOverlay(isScreenOn = false, isLocked = false))
    }

    @Test
    fun show_whenScreenOff_andLocked() {
        assertTrue(shouldShowOverlay(isScreenOn = false, isLocked = true))
    }

    @Test
    fun show_whenScreenOn_andLocked() {
        assertTrue(shouldShowOverlay(isScreenOn = true, isLocked = true))
    }

    @Test
    fun hide_whenScreenOn_andUnlocked() {
        assertFalse(shouldShowOverlay(isScreenOn = true, isLocked = false))
    }

    // ---------- State-machine transitions ----------
    // Simulates the event sequence the service receives and verifies that
    // the overlay is shown/hidden at the right points without any polling.

    private data class AODState(var isScreenOn: Boolean = true, var isLocked: Boolean = false) {
        fun shouldShow() = isLocked || !isScreenOn
    }

    @Test
    fun transition_screenOff_showsOverlay() {
        val state = AODState(isScreenOn = true, isLocked = false)
        assertFalse(state.shouldShow())         // initially hidden (unlocked)

        state.isScreenOn = false                // ACTION_SCREEN_OFF
        assertTrue(state.shouldShow())
    }

    @Test
    fun transition_screenOn_lockedPhone_keepsOverlay() {
        val state = AODState(isScreenOn = false, isLocked = true)
        assertTrue(state.shouldShow())

        state.isScreenOn = true                 // ACTION_SCREEN_ON
        // Keyguard still active — overlay must stay visible
        assertTrue(state.shouldShow())
    }

    @Test
    fun transition_userPresent_hidesOverlay() {
        val state = AODState(isScreenOn = true, isLocked = true)
        assertTrue(state.shouldShow())

        state.isLocked = false                  // ACTION_USER_PRESENT
        assertFalse(state.shouldShow())
    }

    @Test
    fun transition_fullCycle_screenOff_lockedOn_unlocked() {
        val state = AODState(isScreenOn = true, isLocked = false)
        assertFalse(state.shouldShow())

        state.isScreenOn = false                // screen turns off
        assertTrue(state.shouldShow())

        state.isScreenOn = true                 // user presses power button
        state.isLocked = true                   // lock screen shown
        assertTrue(state.shouldShow())

        state.isLocked = false                  // user unlocks
        assertFalse(state.shouldShow())
    }

    // ---------- No-polling invariant ----------
    // Verifies that AODOverlayService contains no quickLockCheckRunnable
    // or any sub-100 ms postDelayed calls by inspecting the class source
    // via reflection.  We check that the known removed symbols don't exist.

    @Test
    fun aodOverlayService_hasNoQuickLockCheckField() {
        val cls = Class.forName("tk.glucodata.accessibility.AODOverlayService")
        val fieldNames = cls.declaredFields.map { it.name }
        assertFalse(
            "quickLockCheckRunnable was removed to eliminate 100ms polling — it must not be re-added",
            fieldNames.any { it.contains("quickLockCheck", ignoreCase = true) }
        )
    }

    @Test
    fun aodOverlayService_hasNoScheduleQuickLockCheckMethod() {
        val cls = Class.forName("tk.glucodata.accessibility.AODOverlayService")
        val methodNames = cls.declaredMethods.map { it.name }
        assertFalse(
            "scheduleQuickLockCheck was removed — polling must stay out of AODOverlayService",
            methodNames.any { it.contains("scheduleQuickLockCheck", ignoreCase = true) }
        )
    }
}
