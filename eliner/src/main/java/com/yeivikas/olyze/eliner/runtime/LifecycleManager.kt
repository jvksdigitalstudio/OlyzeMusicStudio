package com.yeivikas.olyze.eliner.runtime

import com.yeivikas.olyze.eliner.api.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns and validates [RuntimeState] transitions for [EliNerRuntime].
 *
 * Single Responsibility split from [EliNerRuntime] itself: [EliNerRuntime]
 * orchestrates *what happens* on each transition (starting Core, logging,
 * publishing events); [LifecycleManager] only tracks *whether a transition
 * is legal* and applies it atomically. Same lock-free CAS approach as
 * `com.yeivikas.olyze.eliner.core.EliNerCore`'s private `transitionTo`,
 * for the same reason: never blocks a caller.
 */
class LifecycleManager {
    private val _state = MutableStateFlow(RuntimeState.CREATED)

    /** Current runtime state. */
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /**
     * Attempts to move to [target]. Returns whether it succeeded;
     * [state] is left untouched on failure — never a partial/inconsistent
     * transition.
     */
    fun transitionTo(target: RuntimeState): Boolean {
        var succeeded = false
        _state.update { current ->
            if (RuntimeState.isValidTransition(current, target)) {
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
