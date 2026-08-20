package dev.s7a.strata.runtime.headless

/**
 * Records callback and lifecycle activity from the external headless primitive.
 */
internal class HeadlessProbe {
    /**
     * Lifecycle events in callback order.
     */
    val lifecycle: MutableList<HeadlessLifecycleEvent> = ArrayList()

    /**
     * Number of measure callbacks.
     */
    var measures: Int = 0

    /**
     * Number of layout callbacks.
     */
    var layouts: Int = 0

    /**
     * Number of paint callbacks.
     */
    var paints: Int = 0

    /**
     * Number of semantics callbacks.
     */
    var semantics: Int = 0

    /**
     * Number of local element validations.
     */
    var validations: Int = 0

    /**
     * Number of retained node creations.
     */
    var creations: Int = 0

    /**
     * Threads that executed fixture callbacks.
     */
    val callbackThreads: MutableList<Thread> = ArrayList()

    /**
     * Optional exact failure thrown by painting.
     */
    var paintFailure: Throwable? = null

    /**
     * Optional exact failure thrown by detachment.
     */
    var detachFailure: Throwable? = null

    /**
     * Optional exact failure thrown by disposal.
     */
    var disposeFailure: Throwable? = null
}
