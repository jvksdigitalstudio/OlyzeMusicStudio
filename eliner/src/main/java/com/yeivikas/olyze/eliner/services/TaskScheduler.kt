package com.yeivikas.olyze.eliner.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Organizes execution of tasks across [ExecutionLane]s.
 *
 * Depends on [TaskExecutor] (the interface [ThreadManager] implements),
 * not on `ThreadManager` the concrete class — this is the one exception to
 * "no service depends on another service directly" worth calling out
 * explicitly: Task Scheduler *organizing* execution and Thread Manager
 * *owning* execution contexts is the exact relationship the spec itself
 * describes ("toda tarea deberá pasar por este administrador"). Depending
 * on the interface rather than the class keeps it swappable/testable —
 * e.g. a test can inject a fake [TaskExecutor] backed by
 * `Dispatchers.Unconfined` without touching real threads.
 */
class TaskScheduler(private val executor: TaskExecutor) {

    /** Runs [block] on [lane] immediately. */
    fun submit(lane: ExecutionLane, block: suspend CoroutineScope.() -> Unit): Job =
        executor.scopeFor(lane).launch(block = block)

    /** Runs [block] on [lane] after waiting [delayMillis]. */
    fun submitDelayed(lane: ExecutionLane, delayMillis: Long, block: suspend CoroutineScope.() -> Unit): Job =
        executor.scopeFor(lane).launch {
            delay(delayMillis)
            block()
        }
}
