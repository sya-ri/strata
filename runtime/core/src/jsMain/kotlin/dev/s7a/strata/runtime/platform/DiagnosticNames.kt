package dev.s7a.strata.runtime.platform

/**
 * Uses the available JavaScript class name, or an empty label for anonymous classes.
 */
internal actual fun diagnosticName(value: Any): String = value::class.simpleName.orEmpty()
