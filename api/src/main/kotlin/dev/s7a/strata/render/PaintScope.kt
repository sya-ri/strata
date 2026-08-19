package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.PaintNode

/**
 * Collects drawing commands for one node in its local coordinate space.
 *
 * The scope is owned by the [PaintNode.paint] callback and is valid only until that callback returns.
 * Every access must use the tree's owner thread.
 * Access after the callback or from another thread fails.
 * Commands are retained as the complete local display list in emission order.
 * The runtime does not clip local commands to the node or parent bounds.
 * Checked coordinate arithmetic is the only overflow guard.
 * The tree is poisoned once pipeline work has started only when a callback or scope exception escapes the owning paint callback.
 */
public interface PaintScope {
    /**
     * The node's measured size.
     *
     * This property is available only during the owning paint callback.
     */
    public val size: IntSize

    /**
     * Adds a local fill command.
     *
     * @param localBounds the rectangle to fill.
     * @param color the fill color.
     * @throws IllegalStateException when the scope is no longer valid.
     */
    public fun fillRectangle(
        localBounds: IntRect,
        color: ArgbColor,
    )
}
