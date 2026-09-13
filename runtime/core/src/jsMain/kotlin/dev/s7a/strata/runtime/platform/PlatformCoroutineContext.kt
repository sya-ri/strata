package dev.s7a.strata.runtime.platform

import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Installs generation elements only for the synchronous segment, restoring the previous agent state on return or failure.
 */
@Suppress("UNCHECKED_CAST")
internal actual fun runWithPlatformContext(
    context: CoroutineContext,
    block: Runnable,
) {
    val restorations = ArrayList<() -> Unit>()
    try {
        context.fold(Unit) { _, element ->
            if (element is PlatformThreadContextElement<*>) {
                val typed = element as PlatformThreadContextElement<Any?>
                val previous = typed.updateThreadContext(context)
                restorations.add { typed.restoreThreadContext(context, previous) }
            }
        }
        block.run()
    } finally {
        restorations.asReversed().forEach { restore -> restore() }
    }
}
