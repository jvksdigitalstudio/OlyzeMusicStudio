package com.yeivikas.olyze.eliner.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Generic, atomic, transition-table-validated state holder.
 *
 * Extracted in Fase 3 (Audio Foundation) to avoid writing a *third*
 * hand-rolled copy of this exact pattern — `EliNerCore`'s private
 * `transitionTo` (Fase 1, [EngineState]) and `LifecycleManager`
 * (Fase 2.5, `RuntimeState`) already implement it independently, and this
 * phase needed a third one for `AudioSessionState`. That's real
 * duplication, and this phase's own audit explicitly asks to check for it.
 *
 * **Deliberately not retrofitted onto `EliNerCore`/`LifecycleManager`.**
 * Both are already-shipped, CI-verified code from earlier phases ("no
 * romper ninguna arquitectura implementada anteriormente" is this phase's
 * own rule #2). Touching proven state-machine logic purely for stylistic
 * consolidation — with no compiler available to re-verify the change —
 * is a worse trade than leaving two small, independent, working
 * implementations alone. New state machines from this phase onward use
 * this class instead of copying the pattern a fourth time.
 *
 * Zero Android imports, zero dependency on anything outside
 * `kotlinx.coroutines.flow` — stays in `eliner.core`, the one package
 * every other layer is already allowed to depend on.
 */
class StateMachine<S>(
    initial: S,
    private val isValidTransition: (from: S, to: S) -> Boolean,
) {
    private val _state = MutableStateFlow(initial)

    /** Current state. */
    val state: StateFlow<S> = _state.asStateFlow()

    /**
     * Attempts to move to [target]. Returns whether it succeeded; [state]
     * is left untouched on failure. Lock-free (CAS loop via
     * [MutableStateFlow.update]) — never blocks a caller.
     */
    fun transitionTo(target: S): Boolean {
        var succeeded = false
        _state.update { current ->
            if (isValidTransition(current, target)) {
                succeeded = true
                target
            } else {
                succeeded = false
                current
            }
        }
        return succeeded
    }
}
