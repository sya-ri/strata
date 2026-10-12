package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Tracks physical destruction of the fixed resources owned by one Canvas target or portable layer.
 *
 * The render-thread owner retains this probe until its lifetime permit is released.
 * Implementations may retain native attachments, but never a screen, callback, or retained node.
 * A successful probe is permanent and must not issue GPU work or wait for completion.
 */
internal fun interface FabricNativeCanvasDestruction {
    /**
     * Completes eligible physical retirement after initialization and every GUI use have completed and all close requests succeeded.
     *
     * The portable storage owner calls this only at its already-fenced close boundary, never during an ordinary destruction probe.
     * Backends may destroy their closed owned objects without another submission delay; accepted physical destroys must not repeat on retry.
     * The default preserves adapters whose close already destroys storage or whose backend still owns deferred retirement.
     * No GPU submission, completion wait, or logical-close acknowledgment is permitted.
     *
     * @throws Throwable when physical retirement fails; storage and its permit remain owned for terminal retry.
     */
    @JvmSynthetic
    fun completeRetirement() = Unit

    /**
     * Reports whether every tracked resource was physically destroyed after a successful target close.
     *
     * @return true only when the target set no longer consumes native storage.
     * @throws Throwable when the adapter cannot establish destruction; the device retains ownership and its permit.
     */
    @JvmSynthetic
    fun isDestroyed(): Boolean
}
