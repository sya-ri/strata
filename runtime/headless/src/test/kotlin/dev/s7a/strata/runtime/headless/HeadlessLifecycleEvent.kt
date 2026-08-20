package dev.s7a.strata.runtime.headless

/**
 * Typed lifecycle observations for the headless external primitive fixture.
 */
internal sealed interface HeadlessLifecycleEvent {
    /**
     * The retained node was attached.
     */
    data object Attach : HeadlessLifecycleEvent

    /**
     * The retained node was detached.
     */
    data object Detach : HeadlessLifecycleEvent

    /**
     * The retained node was disposed.
     */
    data object Dispose : HeadlessLifecycleEvent
}
