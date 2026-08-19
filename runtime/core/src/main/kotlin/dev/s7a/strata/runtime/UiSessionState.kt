package dev.s7a.strata.runtime

/**
 * Lifecycle states owned by one UI session.
 */
internal sealed interface UiSessionState {
    /**
     * The session accepts declarations and has not attached a retained tree.
     */
    data object Created : UiSessionState

    /**
     * The session owns an active generation and accepts frame and input work.
     */
    data object Attached : UiSessionState

    /**
     * The session retains its tree and bindings without an active generation.
     */
    data object Detached : UiSessionState

    /**
     * The session records an unrecoverable primary failure until it is closed.
     *
     * @property cause the exact primary failure that poisoned the session.
     */
    data class Failed(
        val cause: Throwable,
    ) : UiSessionState

    /**
     * The session has released all retained ownership.
     */
    data object Closed : UiSessionState
}
