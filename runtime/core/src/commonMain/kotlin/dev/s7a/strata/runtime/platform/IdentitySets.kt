package dev.s7a.strata.runtime.platform

/**
 * Creates an owner-thread mutable set whose membership uses reference identity.
 * The caller owns every retained element until removal or clear.
 */
internal expect fun <T : Any> identitySet(): MutableSet<T>
