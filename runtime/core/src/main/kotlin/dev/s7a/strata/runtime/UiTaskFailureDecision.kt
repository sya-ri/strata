package dev.s7a.strata.runtime

/**
 * Selects whether a handled root coroutine failure leaves its session running or poisons it.
 */
internal enum class UiTaskFailureDecision {
    /**
     * Treats the reported failure as handled without changing the session lifecycle.
     */
    Continue,

    /**
     * Poisons the current session after cleanup.
     */
    FailSession,
}
