package dev.s7a.strata.ui

/**
 * Immediate admission result; accepted remote requests still require an application acknowledgement.
 */
public sealed interface UiOperationResult {
    /**
     * The owner admitted this request; asynchronous application may still fail.
     */
    public data object Accepted : UiOperationResult

    /**
     * The requested control was not admitted and the last applied state is retained.
     */
    public data class Rejected(
        public val reason: UiRejection,
    ) : UiOperationResult
}
