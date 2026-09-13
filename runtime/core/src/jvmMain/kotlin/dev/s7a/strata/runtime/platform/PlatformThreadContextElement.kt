package dev.s7a.strata.runtime.platform

import kotlinx.coroutines.ThreadContextElement

/**
 * Integrates generation restoration with kotlinx.coroutines JVM thread context machinery.
 */
internal actual typealias PlatformThreadContextElement<S> = ThreadContextElement<S>
