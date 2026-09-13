package dev.s7a.strata.runtime.platform

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Preserves the JVM retained engine's reference-identity membership contract.
 */
internal actual fun <T : Any> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
