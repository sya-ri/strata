package dev.s7a.strata.runtime.headless

/**
 * Runs [work], then always attempts [close] once on the calling thread.
 *
 * Returns the work result unless work or cleanup fails.
 * A work failure remains primary; distinct cleanup failures are suppressed once by identity.
 * If work succeeds, a cleanup failure escapes directly.
 */
@JvmSynthetic
@Suppress("TooGenericExceptionCaught")
internal fun <T : Any> completeWithClose(
    work: () -> T,
    close: () -> Unit,
): T {
    var result: T? = null
    var failure: Throwable? = null
    try {
        result = work()
    } catch (workFailure: Throwable) {
        failure = workFailure
    }
    try {
        close()
    } catch (closeFailure: Throwable) {
        val currentFailure = failure
        if (currentFailure == null) {
            failure = closeFailure
        } else {
            appendSuppressedIdentity(currentFailure, closeFailure)
        }
    }
    val capturedFailure = failure
    if (capturedFailure != null) {
        throw capturedFailure
    }
    return checkNotNull(result)
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
