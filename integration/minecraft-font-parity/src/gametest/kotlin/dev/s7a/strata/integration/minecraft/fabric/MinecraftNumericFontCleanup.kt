package dev.s7a.strata.integration.minecraft.fabric

/**
 * Failure-preserving cleanup for caller-owned numeric test resources.
 * Calls remain synchronous on the owner thread and attempt every close even after a prior cleanup fails.
 */
internal object MinecraftNumericFontCleanup {
    /**
     * Always invokes cleanup, retaining the original operation failure and suppressing any later cleanup failure.
     */
    fun <T> preserving(
        operation: () -> T,
        cleanup: () -> Unit,
    ): T {
        val result = runCatching(operation)
        val released = runCatching(cleanup)
        result.exceptionOrNull()?.let { failure ->
            released.exceptionOrNull()?.let { secondary -> if (secondary !== failure) failure.addSuppressed(secondary) }
            throw failure
        }
        released.getOrThrow()
        return result.getOrThrow()
    }

    /**
     * Attempts every supplied resource exactly once and propagates the first failure with subsequent failures suppressed.
     */
    fun closeAll(resources: List<AutoCloseable>) {
        val failures = resources.mapNotNull { resource -> runCatching(resource::close).exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach { failure -> if (failure !== first) first.addSuppressed(failure) }
            throw first
        }
    }
}
