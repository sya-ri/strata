package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi

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

    /**
     * Adds a local image blit command.
     *
     * The immutable [image] reference is retained without copying its pixels.
     * The source is expressed in image pixel coordinates and must be nonempty and contained in [image].
     * The destination is a nonempty local half-open rectangle; core translates it to tree coordinates with checked arithmetic.
     *
     * @param image the immutable source image.
     * @param source the nonempty source rectangle in image coordinates.
     * @param localDestination the nonempty destination rectangle in this node's local coordinates.
     * @throws IllegalArgumentException when either rectangle is empty or the source is outside [image].
     * @throws IllegalStateException when the scope is no longer valid.
     */
    public fun blitImage(
        image: DrawImage,
        source: IntRect,
        localDestination: IntRect,
    )

    /**
     * Adds one opaque platform draw command at a local half-open rectangle.
     *
     * Core retains [command] by reference, translates [localBounds] into tree coordinates, and preserves the command among portable draw and clip commands.
     * Only a matching platform backend may interpret the payload; portable backends reject it before producing output.
     *
     * @param command immutable platform-owned payload.
     * @param localBounds nonempty local bounds used for translation, clipping, and backend placement.
     * @throws IllegalArgumentException when [localBounds] is empty.
     * @throws IllegalStateException when the scope is no longer valid.
     */
    @InternalStrataRuntimeApi
    public fun drawPlatform(
        command: PlatformDrawCommand,
        localBounds: IntRect,
    )
}
