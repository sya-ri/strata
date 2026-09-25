package dev.s7a.strata.ui

/**
 * Owner-thread view of presentation admission and terminal lifecycle.
 */
public sealed interface UiSessionStatus {
    /**
     * No native presentation has been confirmed yet.
     */
    public data object Opening : UiSessionStatus

    /**
     * Ready retains the most recent asynchronous rejection, if any.
     */
    public data class Ready(
        public val rejection: UiRejection? = null,
    ) : UiSessionStatus

    /**
     * A newer requested presentation/input state is waiting for application.
     */
    public data class Switching(
        public val presentation: UiPresentation,
    ) : UiSessionStatus

    /**
     * Terminal ownership with the first reason that ended the UI.
     */
    public data class Closed(
        public val reason: UiCloseReason,
    ) : UiSessionStatus
}
