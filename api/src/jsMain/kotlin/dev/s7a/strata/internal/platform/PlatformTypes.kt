package dev.s7a.strata.internal.platform

import kotlin.reflect.KClass

/**
 * Performs a checked JavaScript class-token cast, rejecting values outside the requested type.
 */
@Suppress("UNCHECKED_CAST")
internal actual fun <T : Any> KClass<T>.castValue(value: Any): T {
    require(isInstance(value)) { "Unexpected value for class token." }
    return value as T
}

/**
 * Returns the simple name supported by JavaScript class tokens.
 */
internal actual fun KClass<*>.diagnosticName(): String? = simpleName
