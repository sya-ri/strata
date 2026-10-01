package dev.s7a.strata.performance

/**
 * Named operation boundaries; readiness and evidence storage are outside steady measurements.
 */
@Suppress("unused") // Public test-only Maven contract also used by downstream workloads.
public enum class PerformancePhase {
    /**
     * Explicit cold initialization and loading.
     */
    Prepare,

    /**
     * First successful presentation.
     */
    Initial,

    /**
     * Settled input without application invalidation.
     */
    Idle,

    /**
     * Observable application-state replacement.
     */
    Update,

    /**
     * Pointer, keyboard, text, scrolling, or viewport navigation.
     */
    Input,

    /**
     * Logical or physical destination replacement.
     */
    Resize,

    /**
     * Detachment and terminal resource release.
     */
    Release,
}
