package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread bridge for one Minecraft screen definition host.
 *
 * Lifecycle, frame, input, and close calls are synchronous and must run on the construction thread.
 * The host retains only its independent core runtime session and does not directly retain the definition object or its metadata.
 * The caller owns every object, including a definition reference, captured by the shared content evaluator.
 * Content evaluates during the first [attach], while later synchronous changes use retained node invalidation before another [frame].
 * The host exposes no coroutine, local-state, source-binding, resource, rendering, or version-specific contract.
 * Core lifecycle, first-frame input gating, failure identity, suppression order, and cleanup behavior are delegated unchanged.
 * Every factory call creates distinct retained ownership with referential identity.
 */
@InternalStrataRuntimeApi
public sealed interface MinecraftUiHost : AutoCloseable {
    /**
     * Attaches this host on its owner thread.
     *
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or from an invalid lifecycle state.
     * @throws Throwable when content, reconciliation, lifecycle, or cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    public fun attach()

    /**
     * Detaches this host on its owner thread while retaining the core tree for reattachment.
     *
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the host is not attached.
     */
    public fun detach()

    /**
     * Produces one immutable frame for the supplied viewport.
     *
     * The viewport is converted to fixed constraints, including zero dimensions.
     *
     * @param viewport the non-negative logical viewport.
     * @return the immutable core frame snapshot.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the host is not attached.
     * @throws Throwable when the frame pipeline or cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    public fun frame(viewport: IntSize): RuntimeUiFrame

    /**
     * Dispatches one pointer event through the most recently committed frame.
     *
     * @param event the pointer event in host coordinates.
     * @return the retained core input result.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the host is not attached.
     * @throws Throwable when pointer dispatch or cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    public fun dispatchPointer(event: PointerEvent): InputResult

    /**
     * Closes this host on its owner thread.
     *
     * Close is idempotent after completion and preserves the core session's exact cleanup failure behavior.
     *
     * @throws IllegalStateException when called from the wrong thread or from another active operation; recursive close from close cleanup is a no-op.
     * @throws Throwable when cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    override fun close()
}
