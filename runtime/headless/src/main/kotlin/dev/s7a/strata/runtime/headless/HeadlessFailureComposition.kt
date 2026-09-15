package dev.s7a.strata.runtime.headless

/**
 * Runs [work], then always attempts [close] once on the calling thread.
 *
 * Returns the work result unless work or cleanup fails.
 * A work failure remains primary; distinct cleanup failures are suppressed once by identity.
 * If work succeeds, a cleanup failure escapes directly.
 */
@JvmSynthetic
internal fun <T : Any> completeWithClose(
    work: () -> T,
    close: () -> Unit,
): T {
    val result = runCatching(work)
    val cleanupFailure = runCatching(close).exceptionOrNull()
    if (cleanupFailure != null) {
        val primary = result.exceptionOrNull() ?: throw cleanupFailure
        appendSuppressedIdentity(primary, cleanupFailure)
    }
    return result.getOrThrow()
}

private fun appendSuppressedIdentity(
    primary: Throwable,
    secondary: Throwable,
) {
    if (secondary === primary) {
        return
    }
    val alreadySuppressed = primary.suppressed.any { suppressed -> suppressed === secondary }
    if (alreadySuppressed.not()) {
        primary.addSuppressed(secondary)
    }
}
