package dev.s7a.strata.runtime.platform

/**
 * Returns a platform class label for detached diagnostics without retaining the supplied object.
 */
internal expect fun diagnosticName(value: Any): String
