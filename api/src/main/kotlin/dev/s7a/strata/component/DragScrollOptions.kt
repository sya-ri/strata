package dev.s7a.strata.component

/**
 * Immutable content-drag policy shared by a scroll viewport and its navigation state.
 * Wheel input, programmatic navigation and independent scrollbars are unaffected.
 *
 * @property enabled whether primary content presses use deferred taps and drag scrolling.
 * When false, descendants receive their ordinary immediate pointer input.
 * @property inertiaEnabled whether a released drag continues moving on explicitly timed frames.
 * @property dragThreshold positive finite movement in viewport-local logical pixels required to cancel a tap and recognize a drag.
 * @property deceleration positive finite exponential velocity-decay rate per second; larger values stop sooner.
 */
public data class DragScrollOptions(
    public val enabled: Boolean = true,
    public val inertiaEnabled: Boolean = true,
    public val dragThreshold: Double = 4.0,
    public val deceleration: Double = 9.0,
) {
    init {
        require(dragThreshold.isFinite() && 0.0 < dragThreshold) { "Drag threshold must be finite and positive." }
        require(deceleration.isFinite() && 0.0 < deceleration) { "Scroll deceleration must be finite and positive." }
    }

    /**
     * Standard reusable policy values.
     */
    public companion object {
        /**
         * Touch-style dragging and inertia with the standard threshold and decay rate.
         */
        public val Default: DragScrollOptions = DragScrollOptions()
    }
}
