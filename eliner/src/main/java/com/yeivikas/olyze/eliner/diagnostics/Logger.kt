package com.yeivikas.olyze.eliner.diagnostics

import com.yeivikas.olyze.eliner.core.EngineError

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
