package dev.s7a.strata.component

/**
 * Immutable content-drag policy shared by a scroll viewport and its navigation state.
 * Wheel input, programmatic navigation and independent scrollbars are unaffected.
 */
public sealed interface DragScrollOptions {
    /**
     * Disables content dragging and leaves descendants on their ordinary immediate pointer protocol.
     */
    public data object Disabled : DragScrollOptions

    /**
     * Enables deferred taps and primary-pointer content dragging.
     *
     * @property dragThreshold positive finite movement in viewport-local logical pixels required to cancel a tap and recognize a drag.
     * @property inertia released-motion policy, available only while content dragging is enabled.
     */
    public data class Enabled(
        public val dragThreshold: Double = 4.0,
        public val inertia: Inertia = Inertia.Enabled(),
    ) : DragScrollOptions {
        init {
            require(dragThreshold.isFinite() && 0.0 < dragThreshold) { "Drag threshold must be finite and positive." }
        }
    }

    /**
     * Released-motion policy for enabled content dragging.
     */
    public sealed interface Inertia {
        /**
         * Stops the content when the pointer is released.
         */
        public data object Disabled : Inertia

        /**
         * Continues a released drag on explicitly timed frames.
         *
         * @property deceleration positive finite exponential velocity-decay rate per second; larger values stop sooner.
         */
        public data class Enabled(
            public val deceleration: Double = 9.0,
        ) : Inertia {
            init {
                require(deceleration.isFinite() && 0.0 < deceleration) { "Scroll deceleration must be finite and positive." }
            }
        }
    }

    /**
     * Standard reusable policy values.
     */
    public companion object {
        /**
         * Touch-style dragging and inertia with the standard threshold and decay rate.
         */
        public val Default: DragScrollOptions = Enabled()
    }
}
