package dev.s7a.strata.runtime.platform

/**
 * Preserves the binary JVM class label used by existing render diagnostics.
 */
internal actual fun diagnosticName(value: Any): String = value.javaClass.name
