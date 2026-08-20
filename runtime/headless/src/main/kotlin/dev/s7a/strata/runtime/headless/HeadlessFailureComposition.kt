package dev.s7a.strata.runtime.headless

/**
 * Runs work and then cleanup while preserving the exact primary failure and identity of each distinct cleanup failure.
 *
 * Cleanup runs once after successful work and after a work failure on the same caller thread.
 * A cleanup failure becomes primary only when work succeeds; otherwise it is added to the work failure once.
 * The helper does not retain either callback after it returns.
 *
 * @param work the synchronous operation to complete.
 * @param close the synchronous cleanup operation, always attempted after [work].
 * @return the exact successful value returned by [work].
 * @throws Throwable the exact work failure, the exact cleanup failure, or the work failure with distinct cleanup failures suppressed.
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
