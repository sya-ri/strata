@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Presents a destination rectangle as a translated local paint scope.
 *
 * The caller owns the delegate callback lifetime and supplies only coordinates whose checked translation remains representable.
 */
internal class MinecraftRectPaintScope(
    private val delegate: PaintScope,
    private val destination: IntRect,
) : PaintScope {
    override val size: IntSize
        get() {
            delegate.size
            return destination.size
        }

    override fun withClip(
        localBounds: IntRect,
        content: () -> Unit,
    ) {
        delegate.withClip(localBounds.translate(), content)
    }

    override fun fillRectangle(
        localBounds: IntRect,
        color: ArgbColor,
    ) {
        delegate.fillRectangle(localBounds.translate(), color)
    }

    override fun blitImage(
        image: DrawImage,
        source: IntRect,
        localDestination: IntRect,
    ) {
        delegate.blitImage(image, source, localDestination.translate())
    }

    override fun sampledImage(
        image: DrawImage,
        source: FloatRect,
        localDestination: FloatRect,
        tint: ArgbColor,
        alphaCutoff: Float,
    ) {
        val translated =
            FloatRect(
                localDestination.left + destination.left,
                localDestination.top + destination.top,
                localDestination.right + destination.left,
                localDestination.bottom + destination.top,
            )
        delegate.sampledImage(image, source, translated, tint, alphaCutoff)
    }

    override fun drawPlatform(
        command: PlatformDrawCommand,
        localBounds: IntRect,
    ) {
        delegate.drawPlatform(command, localBounds.translate())
    }

    private fun IntRect.translate(): IntRect =
        IntRect(
            Math.addExact(left, destination.left),
            Math.addExact(top, destination.top),
            Math.addExact(right, destination.left),
            Math.addExact(bottom, destination.top),
        )
}
