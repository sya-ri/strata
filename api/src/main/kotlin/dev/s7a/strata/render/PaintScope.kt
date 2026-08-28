package dev.s7a.strata.render

import dev.s7a.strata.geometry.FloatRect
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
     * Clips commands emitted during [content] without changing their local geometry or sampling coordinates.
     *
     * Clips intersect with enclosing clips and accept empty half-open rectangles.
     * The callback runs exactly once, synchronously on the owning paint thread, and is never retained.
     * The clip ends even when the callback throws; an exception escaping the owning paint callback still poisons the tree.
     * This operation does not change [size], coordinate origins, child layout, pointer hit testing, or later commands.
     * The default implementation rejects the capability without invoking [content], preserving existing paint-scope implementations.
     *
     * @param localBounds local half-open clip rectangle, which may extend beyond the node's logical bounds.
     * @param content synchronous drawing work using this same valid scope.
     * @throws IllegalStateException when called outside the owning callback lifetime or thread.
     * @throws UnsupportedOperationException when a custom scope has not implemented explicit paint clipping.
     * @throws Throwable when [content] fails; its original failure propagates after restoring the enclosing clip.
     */
    public fun withClip(
        localBounds: IntRect,
        content: () -> Unit,
    ): Unit = throw UnsupportedOperationException("This paint scope does not support explicit clipping.")

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
     * Adds an image sampled at the final physical pixel centers without rounding its geometry to logical pixels.
     *
     * The immutable [image] is retained by reference and the rectangles use half-open edges.
     * Nearest sampling maps each covered physical pixel center through the original destination into [source].
     * Clipping does not change that mapping.
     * Every normalized source channel, including alpha, is multiplied by the corresponding [tint] channel.
     * A sample whose multiplied alpha is less than [alphaCutoff] is discarded before blending.
     * Tint multiplication is not rounded to eight-bit channels before blending.
     * Access uses the owning paint callback's thread and lifetime, just like [blitImage].
     * The default implementation rejects this capability so existing paint scopes remain source and binary compatible.
     *
     * @param image the immutable source image.
     * @param source the nonempty source rectangle contained in the image, in source pixel coordinates.
     * @param localDestination the nonempty destination in this node's local coordinates.
     * @param tint the multiplicative straight ARGB color, defaulting to opaque white.
     * @param alphaCutoff the finite inclusive minimum alpha in the range from zero to one.
     * @throws IllegalArgumentException when a rectangle is empty, the source is outside [image], or the cutoff is invalid.
     * @throws IllegalStateException when the scope is no longer valid.
     * @throws UnsupportedOperationException when the scope has not implemented sampled images.
     */
    public fun sampledImage(
        image: DrawImage,
        source: FloatRect,
        localDestination: FloatRect,
        tint: ArgbColor = ArgbColor(-1),
        alphaCutoff: Float = 0.1f,
    ): Unit = throw UnsupportedOperationException("This paint scope does not support sampled images.")

    /**
     * Adds a sampled image with explicit source-axis directions while keeping both rectangles normalized.
     *
     * Sampling, tint, clipping, ownership, thread, and callback-lifetime rules are the same as for ordinary [sampledImage].
     * Reversed axes exchange the source endpoints before interpolation, preserving nearest-neighbor ties without copying or reversing pixels.
     * The default implementation delegates [SampledImageOrientation.Normal] to the existing overload and rejects other orientations.
     *
     * @param image the immutable source image retained by reference.
     * @param source the nonempty source rectangle contained in the image.
     * @param localDestination the nonempty normalized destination in this node's local coordinates.
     * @param orientation source-axis directions, independent of rectangle validation.
     * @param tint the multiplicative straight ARGB color, defaulting to opaque white.
     * @param alphaCutoff the finite inclusive minimum alpha in the range from zero to one.
     * @throws IllegalArgumentException when a rectangle is empty, the source is outside [image], or the cutoff is invalid.
     * @throws IllegalStateException when the scope is no longer valid.
     * @throws UnsupportedOperationException when the scope has not implemented the requested sampling orientation.
     */
    public fun sampledImage(
        image: DrawImage,
        source: FloatRect,
        localDestination: FloatRect,
        orientation: SampledImageOrientation,
        tint: ArgbColor = ArgbColor(-1),
        alphaCutoff: Float = 0.1f,
    ) {
        if (orientation == SampledImageOrientation.Normal) {
            sampledImage(image, source, localDestination, tint, alphaCutoff)
        } else {
            throw UnsupportedOperationException("This paint scope does not support reversed sampled images.")
        }
    }

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
