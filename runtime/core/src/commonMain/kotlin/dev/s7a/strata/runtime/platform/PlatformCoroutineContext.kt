package dev.s7a.strata.runtime.platform

import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Runs a dispatched segment with platform-appropriate coroutine generation restoration.
 */
internal expect fun runWithPlatformContext(
    context: CoroutineContext,
    block: Runnable,
)
