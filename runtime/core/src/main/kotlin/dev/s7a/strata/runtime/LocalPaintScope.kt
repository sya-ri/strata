@file:JvmName("PaintPipelineKt") // Preserve the existing JVM facade when separating the collector from the pipeline class.

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.jvm.JvmName
import kotlin.jvm.JvmSynthetic

/**
 * Collects local commands for one retained node.
 */
@OptIn(InternalStrataRuntimeApi::class)
private class LocalPaintScope(
    ownerGuard: OwnerGuard,
    private val nodeSize: IntSize,
) : PaintScope {
    private val guard = ScopeGuard(ownerGuard)

    /**
     * Commands collected during one local paint call.
     */
    val commands: MutableList<LocalDrawCommand> = ArrayList()

    override val size: IntSize
        get() {
            guard.check()
            return nodeSize
        }

    override fun withClip(
        localBounds: IntRect,
        content: () -> Unit,
    ) {
        guard.check()
        commands.add(LocalDrawCommand.PushClip(localBounds))
        val failures = FailureAccumulator()
        try {
            failures.capture(content)
        } finally {
            failures.capture { commands.add(LocalDrawCommand.PopClip) }
        }
        failures.throwIfPresent()
    }

    override fun fillRectangle(
        localBounds: IntRect,
        color: ArgbColor,
    ) {
        guard.check()
        commands.add(LocalDrawCommand.FillRectangle(localBounds, color))
    }

    override fun blitImage(
        image: DrawImage,
        source: IntRect,
        localDestination: IntRect,
    ) {
        guard.check()
        commands.add(LocalDrawCommand.BlitImage(image, source, localDestination))
    }

    override fun sampledImage(
        image: DrawImage,
        source: FloatRect,
        localDestination: FloatRect,
        tint: ArgbColor,
        alphaCutoff: Float,
    ) {
        sampledImage(image, source, localDestination, SampledImageOrientation.Normal, tint, alphaCutoff)
    }

    override fun sampledImage(
        image: DrawImage,
        source: FloatRect,
        localDestination: FloatRect,
        orientation: SampledImageOrientation,
        tint: ArgbColor,
        alphaCutoff: Float,
    ) {
        guard.check()
        commands.add(LocalDrawCommand.SampledImage(image, source, localDestination, tint, alphaCutoff, orientation))
    }

    override fun drawPlatform(
        command: PlatformDrawCommand,
        localBounds: IntRect,
    ) {
        guard.check()
        commands.add(LocalDrawCommand.Platform(command, localBounds))
    }

    /**
     * Records immutable singleton-axis tiling after checking this callback's owner and lifetime.
     */
    fun paintSingleTexelImageTiles(image: DrawImage): Boolean {
        guard.check()
        val bounds = IntRect(0, 0, nodeSize.width, nodeSize.height)
        val command = createSingleTexelImageTiles(image, bounds) ?: return false
        commands.add(command)
        return true
    }

    /**
     * Snapshots commands while the callback scope remains active.
     */
    fun snapshot(): List<LocalDrawCommand> {
        guard.check()
        return commands.toList()
    }

    /**
     * Closes this local collector after the paint callback.
     */
    fun close() {
        guard.close()
    }
}

/**
 * Creates the core's guarded local collector without exposing its private implementation type.
 */
@JvmSynthetic
internal fun createLocalPaintScope(
    ownerGuard: OwnerGuard,
    size: IntSize,
): PaintScope = LocalPaintScope(ownerGuard, size)

/**
 * Copies this core collector's complete ordered display list while its callback scope remains active.
 */
@JvmSynthetic
internal fun snapshotLocalPaintScope(scope: PaintScope): List<LocalDrawCommand> = (scope as LocalPaintScope).snapshot()

/**
 * Expires this core collector after the callback, preserving the existing owner-first guard.
 */
@JvmSynthetic
internal fun closeLocalPaintScope(scope: PaintScope): Unit = (scope as LocalPaintScope).close()

/**
 * Records singleton-axis tiles only through the core's guarded local collector.
 * Foreign scopes and unrepresentable original grids return false without changing their scalar producer.
 * The current paint list retains immutable source/geometry only and releases it through ordinary paint lifecycle.
 * Integer transforms collapse constant axes; fractional transforms recreate original cells and validation failures.
 * No PaintScope capability or application callback is added or retained by this synthetic runtime bridge.
 */
@InternalStrataRuntimeApi
@JvmSynthetic
public fun paintSingleTexelImageTiles(
    scope: PaintScope,
    image: DrawImage,
): Boolean = (scope as? LocalPaintScope)?.paintSingleTexelImageTiles(image) ?: false
