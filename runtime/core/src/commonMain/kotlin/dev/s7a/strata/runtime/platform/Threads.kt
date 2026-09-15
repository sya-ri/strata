package dev.s7a.strata.runtime.platform

/**
 * Returns a stable identity for the current JVM thread or JavaScript agent.
 */
internal expect fun currentThread(): Any

/**
 * Describes an owner identity captured by [currentThread].
 */
internal expect fun threadName(owner: Any): String
