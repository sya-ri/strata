package dev.s7a.strata.internal.platform

import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

/**
 * Preserves JVM checked casts and fully qualified diagnostic names.
 */
internal actual fun <T : Any> KClass<T>.castValue(value: Any): T = javaObjectType.cast(value)

/**
 * Returns the JVM diagnostic name.
 */
internal actual fun KClass<*>.diagnosticName(): String? = qualifiedName
