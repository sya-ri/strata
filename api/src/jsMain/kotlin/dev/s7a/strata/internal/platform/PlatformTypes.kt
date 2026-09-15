package dev.s7a.strata.internal.platform

import kotlin.reflect.KClass

/**
 * Returns the simple name supported by JavaScript class tokens.
 */
internal actual fun KClass<*>.diagnosticName(): String? = simpleName
