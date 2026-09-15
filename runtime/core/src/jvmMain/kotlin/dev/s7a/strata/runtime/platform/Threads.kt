package dev.s7a.strata.runtime.platform

/**
 * Returns a stable identity for the current JVM thread or JavaScript agent.
 */
internal actual fun currentThread(): Any = Thread.currentThread()

/**
 * Describes an owner identity captured by [currentThread].
 */
internal actual fun threadName(owner: Any): String = (owner as Thread).name
