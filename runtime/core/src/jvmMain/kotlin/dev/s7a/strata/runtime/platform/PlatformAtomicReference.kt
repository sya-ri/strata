package dev.s7a.strata.runtime.platform

import java.util.concurrent.atomic.AtomicReference

/**
 * Preserves the JVM atomic reference representation, visibility, and owner-transfer contract.
 */
internal actual typealias PlatformAtomicReference<V> = AtomicReference<V>
