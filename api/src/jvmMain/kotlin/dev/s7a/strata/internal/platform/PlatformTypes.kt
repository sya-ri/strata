package dev.s7a.strata.internal.platform

import kotlin.reflect.KClass

/**
 * Returns the JVM diagnostic name.
 */
internal actual fun KClass<*>.diagnosticName(): String? = qualifiedName
