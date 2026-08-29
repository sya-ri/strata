package dev.s7a.strata.integration.minecraft.fabric

// Why: this cleanup boundary aggregates arbitrary native and assertion failures without skipping independent operations.

/**
 * Attempts every independent loaded Canvas cleanup and preserves the original failure as primary.
 *
 * Operations are invoked once in supplied order on the calling thread; runner-thread callers marshal their own native work to the client thread.
 * Repeated references to the same exception are not self-suppressed; cleanup failures throw only when no original failure exists.
 */
@Suppress("TooGenericExceptionCaught")
internal fun runCanvasTestCleanup(
    primary: Throwable?,
    vararg operations: () -> Unit,
) {
    var failure = primary
    for (operation in operations) {
        try {
            operation()
        } catch (caught: Throwable) {
            val previous = failure
            if (previous == null) {
                failure = caught
            } else if (previous !== caught) {
                previous.addSuppressed(caught)
            }
        }
    }
    if (primary == null) failure?.let { throw it }
}
