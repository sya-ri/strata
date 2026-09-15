package dev.s7a.strata.internal.platform

/**
 * Returns a stable identity for the current JVM thread or JavaScript agent.
 */
internal actual fun currentThread(): Any = Thread.currentThread()
