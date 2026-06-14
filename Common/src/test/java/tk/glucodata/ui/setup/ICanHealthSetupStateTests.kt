package tk.glucodata.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [ICanHealthSetupState] state machine.
 *
 * The state machine is the reducer target the wizard renders. These tests
 * cover the legal/illegal transition matrix, the permission pending-code
 * handoff, and the back-press consumer/dismiss contract.
 */
class ICanHealthSetupStateTests {

    private val identityNormalize: (String) -> String = { it.trim().uppercase() }

    // ---------- ICanHealthSetupStep.canTransitionTo ----------

    @Test
    fun step_canTransitionTo_onboardingToConnectingIsAllowed() {
        assertTrue(ICanHealthSetupStep.ONBOARDING.canTransitionTo(ICanHealthSetupStep.CONNECTING))
    }

    @Test
    fun step_canTransitionTo_connectingToSuccessIsAllowed() {
        assertTrue(ICanHealthSetupStep.CONNECTING.canTransitionTo(ICanHealthSetupStep.SUCCESS))
    }

    @Test
    fun step_canTransitionTo_onboardingToSuccessIsRejected() {
        // Skipping the connecting phase should be illegal — this is the
        // kind of state-graph inconsistency Compose 1.11 doesn't tolerate.
        assertFalse(ICanHealthSetupStep.ONBOARDING.canTransitionTo(ICanHealthSetupStep.SUCCESS))
    }

    @Test
    fun step_canTransitionTo_successToOnboardingIsAllowedForRetry() {
        assertTrue(ICanHealthSetupStep.SUCCESS.canTransitionTo(ICanHealthSetupStep.ONBOARDING))
    }

    @Test
    fun step_canTransitionTo_anyStepToItselfIsAllowed() {
        // The state machine tolerates self-loops so reducers can be
        // idempotent (e.g. LaunchedEffect re-entries).
        ICanHealthSetupStep.values().forEach { step ->
            assertTrue("$step -> $step should be allowed", step.canTransitionTo(step))
        }
    }

    // ---------- ICanHealthSetupState.isAttachable ----------

    @Test
    fun state_isAttachable_acceptsCodeAtMinLength() {
        val state = ICanHealthSetupState()
        val code = "A".repeat(ICanHealthSetupState.MIN_ATTACH_CODE_LEN)
        assertTrue(state.isAttachable(code, identityNormalize))
    }

    @Test
    fun state_isAttachable_acceptsCodeAtMaxLength() {
        val state = ICanHealthSetupState()
        val code = "A".repeat(ICanHealthSetupState.MAX_ATTACH_CODE_LEN)
        assertTrue(state.isAttachable(code, identityNormalize))
    }

    @Test
    fun state_isAttachable_rejectsCodeShorterThanMin() {
        val state = ICanHealthSetupState()
        val code = "A".repeat(ICanHealthSetupState.MIN_ATTACH_CODE_LEN - 1)
        assertFalse(state.isAttachable(code, identityNormalize))
    }

    @Test
    fun state_isAttachable_rejectsCodeLongerThanMax() {
        val state = ICanHealthSetupState()
        val code = "A".repeat(ICanHealthSetupState.MAX_ATTACH_CODE_LEN + 1)
        assertFalse(state.isAttachable(code, identityNormalize))
    }

    @Test
    fun state_isAttachable_rejectsBlankCode() {
        val state = ICanHealthSetupState()
        assertFalse(state.isAttachable("", identityNormalize))
        assertFalse(state.isAttachable("   ", identityNormalize))
    }

    // ---------- ICanHealthSetupState.startAttach ----------

    @Test
    fun state_startAttach_validCodeTransitionsToConnecting() {
        val state = ICanHealthSetupState()
        val code = "ABCDEFGH"
        val outcome = state.startAttach(code, identityNormalize)
        assertEquals(ICanHealthAttachOutcome.Started, outcome)
        assertEquals(ICanHealthSetupStep.CONNECTING, state.currentStep)
        assertEquals(code, state.selectedSensorLabel)
        assertEquals(code, state.lastOnboardingCode)
    }

    @Test
    fun state_startAttach_invalidCodeReturnsInvalidCodeAndDoesNotTransition() {
        val state = ICanHealthSetupState()
        val outcome = state.startAttach("SHORT", identityNormalize)
        assertEquals(ICanHealthAttachOutcome.InvalidCode, outcome)
        assertEquals(ICanHealthSetupStep.ONBOARDING, state.currentStep)
    }

    @Test
    fun state_startAttach_rejectsIllegalSkips() {
        // Get the state to SUCCESS via legal transitions, then try
        // startAttach from SUCCESS — it should still attempt the
        // transition but the state machine should clamp it.
        val state = ICanHealthSetupState()
        state.startAttach("ABCDEFGH", identityNormalize)
        state.markAttached(ICanHealthAttachResult.Added("ABCDEFGH"))
        assertEquals(ICanHealthSetupStep.SUCCESS, state.currentStep)

        state.startAttach("ZZZZZZZZ", identityNormalize)
        // SUCCESS -> CONNECTING is illegal; state should stay at SUCCESS.
        assertEquals(ICanHealthSetupStep.SUCCESS, state.currentStep)
    }

    // ---------- ICanHealthSetupState.requestPermissionsThenAttach ----------

    @Test
    fun state_requestPermissionsThenAttach_persistsPendingCode() {
        val state = ICanHealthSetupState()
        val outcome = state.requestPermissionsThenAttach("ABCDEFGH", identityNormalize)
        assertEquals(ICanHealthAttachOutcome.NeedsPermission("ABCDEFGH"), outcome)
        // Code is persisted so the post-permission callback can resume.
        val resumed = state.onPermissionResult(granted = true)
        assertEquals("ABCDEFGH", resumed)
    }

    @Test
    fun state_requestPermissionsThenAttach_doesNotPersistWhenInvalid() {
        val state = ICanHealthSetupState()
        state.requestPermissionsThenAttach("X", identityNormalize)
        // Invalid code is not persisted, so onPermissionResult returns null.
        assertNull(state.onPermissionResult(granted = true))
    }

    @Test
    fun state_onPermissionResult_clearsPendingCodeRegardlessOfGrant() {
        val state = ICanHealthSetupState()
        state.requestPermissionsThenAttach("ABCDEFGH", identityNormalize)
        // First call returns the pending code.
        assertEquals("ABCDEFGH", state.onPermissionResult(granted = true))
        // Second call returns null because the pending code was cleared.
        assertNull(state.onPermissionResult(granted = true))
    }

    @Test
    fun state_onPermissionResult_returnsNullWhenDenied() {
        val state = ICanHealthSetupState()
        state.requestPermissionsThenAttach("ABCDEFGH", identityNormalize)
        // User denied — caller should not attempt to startAttach.
        assertNull(state.onPermissionResult(granted = false))
    }

    // ---------- ICanHealthSetupState.markAttached ----------

    @Test
    fun state_markAttached_addedTransitionsToSuccess() {
        val state = ICanHealthSetupState()
        state.startAttach("ABCDEFGH", identityNormalize)
        state.markAttached(ICanHealthAttachResult.Added("ABCDEFGH"))
        assertEquals(ICanHealthSetupStep.SUCCESS, state.currentStep)
    }

    @Test
    fun state_markAttached_failedFallsBackToOnboarding() {
        val state = ICanHealthSetupState()
        state.startAttach("ABCDEFGH", identityNormalize)
        state.markAttached(ICanHealthAttachResult.Failed(RuntimeException("bluetooth off")))
        assertEquals(ICanHealthSetupStep.ONBOARDING, state.currentStep)
    }

    // ---------- ICanHealthSetupState.onBack ----------

    @Test
    fun state_onBack_closesManualEntryFirst() {
        val state = ICanHealthSetupState()
        state.openManualEntry()
        val outcome = state.onBack()
        assertEquals(BackOutcome.Consumed, outcome)
        assertFalse(state.showManualEntry)
    }

    @Test
    fun state_onBack_fromOnboardingDismissesWizard() {
        val state = ICanHealthSetupState()
        val outcome = state.onBack()
        assertEquals(BackOutcome.Dismiss, outcome)
    }

    @Test
    fun state_onBack_fromConnectingGoesBackToOnboarding() {
        val state = ICanHealthSetupState()
        state.startAttach("ABCDEFGH", identityNormalize)
        assertEquals(ICanHealthSetupStep.CONNECTING, state.currentStep)

        val outcome = state.onBack()
        assertEquals(BackOutcome.Consumed, outcome)
        assertEquals(ICanHealthSetupStep.ONBOARDING, state.currentStep)
    }

    @Test
    fun state_onBack_fromSuccessGoesBackToOnboarding() {
        val state = ICanHealthSetupState()
        state.startAttach("ABCDEFGH", identityNormalize)
        state.markAttached(ICanHealthAttachResult.Added("ABCDEFGH"))
        assertEquals(ICanHealthSetupStep.SUCCESS, state.currentStep)

        val outcome = state.onBack()
        assertEquals(BackOutcome.Consumed, outcome)
        assertEquals(ICanHealthSetupStep.ONBOARDING, state.currentStep)
    }

    // ---------- ICanHealthSetupState.openManualEntry / dismissManualEntry ----------

    @Test
    fun state_manualEntry_isIdempotent() {
        val state = ICanHealthSetupState()
        state.openManualEntry()
        state.openManualEntry()
        assertTrue(state.showManualEntry)
    }

    @Test
    fun state_dismissManualEntry_closesDialog() {
        val state = ICanHealthSetupState()
        state.openManualEntry()
        state.dismissManualEntry()
        assertFalse(state.showManualEntry)
    }
}
