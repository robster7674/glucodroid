// JugglucoNG — iCanHealth Setup Wizard state machine.
//
// Extracted from ICanHealthSetupWizard so the state transitions are testable
// without a Compose runtime and so the wizard itself is just a thin
// presentation layer over this state machine.
//
// Compose 1.11.x + Material3 1.4.x + Kotlin 2.3.21 + R8 -repackageclasses
// changed how `object` singletons are read during the first measure pass.
// The new versions expect state-bearing composables to be deterministic
// reducers over an explicit state model rather than capturing mutable
// state inside lambdas. This class is the reducer target.

package tk.glucodata.ui.setup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Discrete phases the iCanHealth setup wizard moves through.
 *
 * Kept as an explicit enum so [ICanHealthSetupStep.canTransitionTo] can
 * reject illegal moves (e.g. ONBOARDING -> SUCCESS) — which is the kind of
 * guard Compose 1.11's stricter recomposition model expects.
 */
enum class ICanHealthSetupStep {
    ONBOARDING,
    CONNECTING,
    SUCCESS;

    fun canTransitionTo(next: ICanHealthSetupStep): Boolean {
        if (next == this) return true
        return when (this) {
            ONBOARDING -> next == CONNECTING
            CONNECTING -> next == SUCCESS || next == ONBOARDING
            SUCCESS -> next == ONBOARDING
        }
    }
}

/**
 * Outcome of an [ICanHealthSetupState.startAttach] call.
 *
 * Modeled as a sealed result so callers can distinguish "needs permissions"
 * (which the wizard shows a launcher for) from "attach started" (which moves
 * to CONNECTING) without coupling the wizard to coroutine internals.
 */
sealed interface ICanHealthAttachOutcome {
    object Started : ICanHealthAttachOutcome
    data class NeedsPermission(val normalizedCode: String) : ICanHealthAttachOutcome
    object InvalidCode : ICanHealthAttachOutcome
}

/**
 * Result of a [ICanHealthSetupState.markAttached] call.
 *
 * Separates the "addSensor returned an id" case from the "addSensor threw"
 * case so the wizard can drive `currentStep` deterministically without
 * inspecting coroutine exception state.
 */
sealed interface ICanHealthAttachResult {
    data class Added(val sensorId: String) : ICanHealthAttachResult
    data class Failed(val cause: Throwable) : ICanHealthAttachResult
}

/**
 * Pure state machine for the iCanHealth setup wizard.
 *
 * Composability: this class is @Stable so Compose's skipping machinery
 * treats field reads as cheap. The wizard observes [currentStep] and
 * [showManualEntry] via Compose state; mutating them triggers recomposition.
 *
 * Concurrency: [startAttach] / [markAttached] are expected to be called from
 * the main thread (the wizard uses rememberCoroutineScope). The underlying
 * mutableStateOf fields are not thread-safe, by design.
 */
@Stable
class ICanHealthSetupState(
    initialStep: ICanHealthSetupStep = ICanHealthSetupStep.ONBOARDING,
) {
    var currentStep by mutableStateOf(initialStep)
        private set

    var lastOnboardingCode by mutableStateOf("")
        private set

    var selectedSensorLabel by mutableStateOf("")
        private set

    var showManualEntry by mutableStateOf(false)
        private set

    var pendingAttachCode by mutableStateOf<String?>(null)
        private set

    /**
     * Returns true if [raw] is long enough (8..13 chars after normalization)
     * to attempt an attach. Pure function — no side effects.
     */
    fun isAttachable(raw: String, normalize: (String) -> String): Boolean {
        val normalized = normalize(raw)
        return normalized.length in MIN_ATTACH_CODE_LEN..MAX_ATTACH_CODE_LEN
    }

    /**
     * Attempts to start an attach with the given normalized code. Returns the
     * outcome the wizard should drive. Does not itself call into the driver —
     * the wizard does that asynchronously and then calls [markAttached] with
     * the result.
     */
    fun startAttach(rawCode: String, normalize: (String) -> String): ICanHealthAttachOutcome {
        val normalized = normalize(rawCode)
        if (normalized.length !in MIN_ATTACH_CODE_LEN..MAX_ATTACH_CODE_LEN) {
            return ICanHealthAttachOutcome.InvalidCode
        }
        lastOnboardingCode = normalized
        selectedSensorLabel = normalized
        moveTo(ICanHealthSetupStep.CONNECTING)
        return ICanHealthAttachOutcome.Started
    }

    /**
     * Records an attach that required permissions before proceeding. The
     * wizard uses this to remember the code so the post-permission callback
     * can re-enter [startAttach]. The normalized code is persisted to
     * [pendingAttachCode] so [onPermissionResult] can return it.
     */
    fun requestPermissionsThenAttach(rawCode: String, normalize: (String) -> String): ICanHealthAttachOutcome {
        val normalized = normalize(rawCode)
        if (normalized.length !in MIN_ATTACH_CODE_LEN..MAX_ATTACH_CODE_LEN) {
            return ICanHealthAttachOutcome.InvalidCode
        }
        lastOnboardingCode = normalized
        pendingAttachCode = normalized
        return ICanHealthAttachOutcome.NeedsPermission(normalized)
    }

    /**
     * Mark the pending permission request as fulfilled, with [granted] true
     * if the user allowed the requested permissions. If granted and we still
     * have a pending code, return that code so the wizard can call
     * [startAttach] with it; otherwise null.
     */
    fun onPermissionResult(granted: Boolean): String? {
        val pending = pendingAttachCode
        pendingAttachCode = null
        if (granted && pending != null) return pending
        return null
    }

    /**
     * Records the outcome of an async `addSensor` call. Transitions to
     * SUCCESS on success, falls back to ONBOARDING on failure (and returns
     * the throwable to the caller for logging).
     */
    fun markAttached(result: ICanHealthAttachResult) {
        when (result) {
            is ICanHealthAttachResult.Added -> moveTo(ICanHealthSetupStep.SUCCESS)
            is ICanHealthAttachResult.Failed -> moveTo(ICanHealthSetupStep.ONBOARDING)
        }
    }

    /**
     * Opens the manual entry dialog. Idempotent.
     */
    fun openManualEntry() {
        showManualEntry = true
    }

    /**
     * Closes the manual entry dialog.
     */
    fun dismissManualEntry() {
        showManualEntry = false
    }

    /**
     * Handle a back press. Returns true if the wizard should consume the
     * back press; false if the wizard should be dismissed entirely.
     */
    fun onBack(): BackOutcome = when {
        showManualEntry -> {
            dismissManualEntry()
            BackOutcome.Consumed
        }
        currentStep == ICanHealthSetupStep.ONBOARDING -> BackOutcome.Dismiss
        else -> {
            moveTo(ICanHealthSetupStep.ONBOARDING)
            BackOutcome.Consumed
        }
    }

    /**
     * Centralized step transition that rejects illegal moves. Illegal moves
     * are ignored — this matches Compose 1.11's stricter recomposition model
     * which doesn't tolerate state-graph inconsistencies during a single
     * measure pass.
     */
    private fun moveTo(next: ICanHealthSetupStep) {
        if (currentStep.canTransitionTo(next)) {
            currentStep = next
        }
    }

    companion object {
        const val MIN_ATTACH_CODE_LEN: Int = 8
        const val MAX_ATTACH_CODE_LEN: Int = 13
    }
}

sealed interface BackOutcome {
    object Consumed : BackOutcome
    object Dismiss : BackOutcome
}
