package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns one portable GUI layer's native storage and any CPU upload storage needed until completion.
 *
 * A version adapter allocates this resource only after reserving its exact layer extent in a [NativeGuiResourceSet].
 * Ownership transfers to [NativeGuiResources.add] before initialization or upload can fail, including partially constructed native resources.
 * Every operation belongs to the device render thread; implementations retain no screen, retained node, or frame.
 */
@InternalStrataRuntimeApi
public interface NativeGuiResource : AutoCloseable {
    /**
     * Requests release after initialization and every queued GUI use have completed.
     *
     * The owner never repeats a successful close and retries a failed close only once during terminal cleanup.
     * Implementations attempt every independent owned release, skip previously accepted releases on retry, and preserve the first failure with later failures suppressed.
     * This operation may enqueue native destruction but must not submit new GPU work or wait for unconsumed GUI commands.
     *
     * @throws Throwable when any owned release fails; ownership and the resource-set permit remain retained.
     */
    override fun close()

    /**
     * Acknowledges physical destruction after a successful close request without waiting or issuing work.
     *
     * The default is valid only for adapters that destroy all owned resources synchronously during close.
     * Asynchronous adapters report true only after their actual native destruction callbacks complete.
     * A true result is permanent and permits the owner to forget this resource once its whole set is destroyed.
     *
     * @return whether every native allocation owned by this resource has been physically destroyed.
     * @throws Throwable when destruction cannot be established; the owner keeps the resource and its permit.
     */
    public fun isDestroyed(): Boolean = true
}
