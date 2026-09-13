package dev.s7a.strata.runtime.platform

import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * The JVM coroutine dispatcher already installs ThreadContextElement state around its continuation.
 */
internal actual fun runWithPlatformContext(
    context: CoroutineContext,
    block: Runnable,
) {
    block.run()
}
