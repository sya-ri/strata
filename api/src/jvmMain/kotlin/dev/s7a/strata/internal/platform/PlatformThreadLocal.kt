package dev.s7a.strata.internal.platform

/**
 * Retains the JVM thread-local representation and releases captures on removal.
 */
internal actual typealias PlatformThreadLocal<T> = ThreadLocal<T>
