package dev.s7a.strata.internal.platform

import kotlin.reflect.KClass

/**

 * Validates and casts [value] using the platform's class token without exposing platform reflection.

 */
internal expect fun <T : Any> KClass<T>.castValue(value: Any): T

/**

 * Returns the diagnostic name supported by the platform's class token.

 */
internal expect fun KClass<*>.diagnosticName(): String?
