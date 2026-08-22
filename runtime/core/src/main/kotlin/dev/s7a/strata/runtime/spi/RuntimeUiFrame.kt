package dev.s7a.strata.runtime.spi

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable output of one successful runtime UI session frame.
 *
 * The owner thread creates the frame and owns the detached, defensive snapshots of its output collections.
 * After construction, all frame reads are safe from any thread when the contained public value contracts are honored.
 * The frame does not retain the session, content description, retained tree, or mutable source collections.
 * A session may return the same frame and collection instances for consecutive clean calls, so consumers must treat identity as an optimization rather than a semantic revision.
 */
@InternalStrataRuntimeApi
public sealed interface RuntimeUiFrame {
    /**
     * The measured root size produced by the owner-thread frame pass.
     */
    public val size: IntSize

    /**
     * The detached, unmodifiable drawing-command snapshot in tree coordinates and emission order.
     */
    public val drawCommands: List<DrawCommand>

    /**
     * The detached, unmodifiable semantics snapshot in tree coordinates and traversal order.
     */
    public val semantics: List<SemanticsEntry>
}
