@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Collects one synchronous text paint for independent command and physical-pixel comparisons.
 *
 * The test owns the collector and its mutable command list; no runtime or native resources are retained.
 * Platform commands are rejected because both visibility paths must remain portable.
 */
internal class MinecraftTextRecordingScope : PaintScope {
    override val size: IntSize = IntSize(64, 32)

    /**
     * Commands in original submission order, owned by this test capture.
     */
    val commands: MutableList<DrawCommand> = mutableListOf()

    override fun fillRectangle(
        localBounds: IntRect,
        color: ArgbColor,
    ) {
        commands.add(DrawCommand.FillRectangle(localBounds, color))
    }

    override fun blitImage(
        image: DrawImage,
        source: IntRect,
        localDestination: IntRect,
    ) {
        commands.add(DrawCommand.BlitImage(image, source, localDestination))
    }

    override fun sampledImage(
        image: DrawImage,
        source: FloatRect,
        localDestination: FloatRect,
        orientation: SampledImageOrientation,
        tint: ArgbColor,
        alphaCutoff: Float,
    ) {
        commands.add(DrawCommand.SampledImage(image, source, localDestination, tint, alphaCutoff, orientation))
    }

    override fun drawPlatform(
        command: PlatformDrawCommand,
        localBounds: IntRect,
    ): Unit = error("Text must not emit platform commands.")
}
