package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Tracks physical destruction of the fixed resources owned by one Canvas target set.
 *
 * The render-thread target owns this probe until its lifetime permit is released.
 * Implementations may retain native attachments, but never a screen, callback, or retained node.
 * A successful probe is permanent and must not issue GPU work or wait for completion.
 */
internal fun interface FabricNativeCanvasDestruction {
    /**
     * Reports whether every tracked resource was physically destroyed after a successful target close.
     *
     * @return true only when the target set no longer consumes native storage.
     * @throws Throwable when the adapter cannot establish destruction; the device retains ownership and its permit.
     */
    @JvmSynthetic
    fun isDestroyed(): Boolean
}
