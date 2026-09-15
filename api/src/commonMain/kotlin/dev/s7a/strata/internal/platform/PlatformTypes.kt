package dev.s7a.strata.internal.platform

import kotlin.reflect.KClass

/**
 * Returns the diagnostic name supported by the platform's class token.
 */
internal expect fun KClass<*>.diagnosticName(): String?
