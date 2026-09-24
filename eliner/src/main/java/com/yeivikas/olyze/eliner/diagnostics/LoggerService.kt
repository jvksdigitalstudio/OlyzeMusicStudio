package com.yeivikas.olyze.eliner.diagnostics

import com.yeivikas.olyze.eliner.core.EngineError
import com.yeivikas.olyze.eliner.core.EngineErrorSeverity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Severity of a single [LogEntry]. Five levels, exactly as specified. */
enum class LogLevel { DEBUG, INFO, WARNING, ERROR, CRITICAL }

/**
 * A single log record. Immutable, timestamped at creation — this is the
 * unit [LoggerService] deals in, and what a future "Logs / Registro de
 * errores" screen would render one row per.
 */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val throwable: Throwable? = null,
)

/**
 * Something that wants to receive every [LogEntry] as it's logged — e.g. a
 * future file writer, a crash-report uploader, or (in this phase) nothing
 * at all, since no such sink exists yet. [LoggerService] works correctly
 * with zero sinks registered; [entries] alone is enough for anyone to
 * observe the log stream reactively.
 */
interface LogSink {
    fun onLog(entry: LogEntry)
}

/**
 * The contract [com.yeivikas.olyze.eliner.runtime.RuntimeContext] and any
 * other future consumer should depend on, instead of [LoggerService]
 * directly. Added in Fase 2.5 (Runtime Foundation) — no consumer existed
 * before this phase that needed the abstraction, so it wasn't created
 * speculatively in Fase 2.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warning(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
    fun critical(tag: String, message: String, throwable: Throwable? = null)
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
    fun log(error: EngineError)
}

/**
 * Centralized logging service — the foundation the future "Logs / Registro
 * de errores" module will be built on.
 *
 * Two ways to consume it: collect [entries] (reactive, e.g. from a future
 * UI screen), or register a [LogSink] (imperative, e.g. a future file
 * writer that shouldn't need coroutines).
 *
 * Zero Android imports. Zero dependency on any other Foundation Service —
 * its only outside dependency is `eliner.core.EngineError`, which is a
 * lower layer (Core Foundation), not a peer service. See [log] (the
 * `EngineError` overload) for why that dependency exists.
 */
class LoggerService : Logger {
    private val lock = Any()
    private val sinks = mutableListOf<LogSink>()

    private val _entries = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    /** Stream of every entry logged from this point forward. */
    val entries: SharedFlow<LogEntry> = _entries.asSharedFlow()

    fun addSink(sink: LogSink) = synchronized(lock) { sinks.add(sink) }

    fun removeSink(sink: LogSink) = synchronized(lock) { sinks.remove(sink) }

    override fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    override fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    override fun warning(tag: String, message: String) = log(LogLevel.WARNING, tag, message)

    override fun error(tag: String, message: String, throwable: Throwable?) =
        log(LogLevel.ERROR, tag, message, throwable)

    override fun critical(tag: String, message: String, throwable: Throwable?) =
        log(LogLevel.CRITICAL, tag, message, throwable)

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(level, tag, message, System.currentTimeMillis(), throwable)
        _entries.tryEmit(entry)
        val sinksSnapshot = synchronized(lock) { sinks.toList() }
        sinksSnapshot.forEach { it.onLog(entry) }
    }

    /**
     * Logs an [EngineError] reported by Core Foundation, mapping its
     * [EngineErrorSeverity] to the matching [LogLevel]. This is the real
     * answer to "toda excepción importante del motor deberá poder
     * registrarse aquí" — a direct, synchronous way to log an
     * [EngineError] the moment it's available, with no coroutine
     * subscription required.
     *
     * This does NOT auto-subscribe to any [com.yeivikas.olyze.eliner.core.EliNerCore]
     * instance's error stream — that wiring needs a coroutine scope, and
     * deciding which scope owns that subscription is an orchestration
     * decision for whoever composes the full engine (see
     * `eliner.services.ThreadManager`), not something Diagnostics should
     * assume for itself.
     */
    override fun log(error: EngineError) {
        val level = when (error.severity) {
            EngineErrorSeverity.WARNING -> LogLevel.WARNING
            EngineErrorSeverity.ERROR -> LogLevel.ERROR
            EngineErrorSeverity.FATAL -> LogLevel.CRITICAL
        }
        log(level, tag = error.moduleId ?: "EliNerCore", message = "[${error.code}] ${error.message}", throwable = error.cause)
    }
}
