package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasContext
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasRenderer
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureLease
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureOrigin
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureProvider
import dev.s7a.strata.runtime.minecraft.fabric.canvasSource

/**
 * Changes real native texture orientation and custom output on every successful attachment capture.
 *
 * All state and native resources belong to the client thread; the fixture stays external to its screen and command snapshots.
 * Texture leases alternate the orientation of the same immutable native texels, while renderers alternate a native clear and transparent output.
 * Every callback supplies its own matching immutable snapshot, so capturing a different native generation cannot pass by comparing constant colors.
 * Close is legal only after all real capture leases and renderer instances have retired through the native lifetime owner.
 */
internal class MinecraftCanvasGenerationFixture(
    private val resources: MinecraftCanvasTestResources,
) : AutoCloseable {
    private val textureSnapshot =
        createDrawImage(
            IntSize(2, 2),
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt()),
        )
    private var nextOrigin = MinecraftCanvasTextureOrigin.TopLeft
    private var lastOrigin = MinecraftCanvasTextureOrigin.TopLeft
    private var nextPaint = PaintPhase.Painted
    private var lastPaint = PaintPhase.Painted

    /**
     * Actual native backend description copied before the external resource is released.
     */
    internal val backendDescription: String = resources.backendDescription

    /**
     * Number of acquired immutable native leases, updated only on the client thread.
     */
    internal var textureGeneration: Int = 0
        private set

    /**
     * Number of texture leases whose real capture fence has allowed release.
     */
    internal var releasedLeases: Int = 0
        private set

    /**
     * Number of actual custom render callbacks, excluding cached paint and additional hover layout frames.
     */
    internal var rendererGeneration: Int = 0
        private set

    /**
     * Number of independently initialized renderer instances, confined to the client thread.
     */
    internal var openedRenderers: Int = 0
        private set

    /**
     * Number of renderer instances whose actual last-use fence permitted resource release.
     */
    internal var closedRenderers: Int = 0
        private set

    /**
     * Most recent physical custom target extent, used to wait for a requested native GUI scale.
     */
    internal var physicalSize: IntSize = IntSize.Zero
        private set

    /**
     * Client-thread callback after native rendering and before publication, cleared before fixture cleanup.
     */
    internal var afterNativeRender: (() -> Unit)? = null

    /**
     * Immutable orientation discriminator for the most recently captured texture, read only on the client thread.
     */
    internal val textureOrigin: MinecraftCanvasTextureOrigin
        get() = lastOrigin

    /**
     * Independent literal expected at the normalized native texture's upper-left texel for the current captured generation.
     */
    internal val expectedTextureArgb: Int
        get() =
            when (lastOrigin) {
                MinecraftCanvasTextureOrigin.TopLeft -> 0xFFFF0000.toInt()
                MinecraftCanvasTextureOrigin.BottomLeft -> 0xFF0000FF.toInt()
            }

    /**
     * Independent literal expected after the current custom output is composited onto the scene's opaque black background.
     */
    internal val expectedRendererArgb: Int
        get() =
            when (lastPaint) {
                PaintPhase.Painted -> 0xFF008000.toInt()
                PaintPhase.Clear -> 0xFF000000.toInt()
            }

    /**
     * Externally reusable texture source whose leases alternate the normalization of immutable native input pixels.
     */
    internal val textureSource: CanvasSource = canvasSource(MinecraftCanvasTextureProvider { acquireTexture() })

    /**
     * Externally reusable depth-enabled factory with one independently fenced renderer per attachment.
     */
    internal val rendererSource: CanvasSource = canvasSource(depth = true) { createRenderer() }

    private fun acquireTexture(): MinecraftCanvasTextureLease {
        val origin = nextOrigin
        nextOrigin =
            when (origin) {
                MinecraftCanvasTextureOrigin.TopLeft -> MinecraftCanvasTextureOrigin.BottomLeft
                MinecraftCanvasTextureOrigin.BottomLeft -> MinecraftCanvasTextureOrigin.TopLeft
            }
        val lease = resources.lease(origin)
        lastOrigin = origin
        textureGeneration++
        return object : MinecraftCanvasTextureLease by lease {
            override val snapshot: DrawImage = textureSnapshot
            private var closed = false

            override fun close() {
                check(closed.not()) { "A changing-generation native lease was closed twice." }
                lease.close()
                closed = true
                releasedLeases++
            }
        }
    }

    private fun createRenderer(): MinecraftCanvasRenderer {
        val probe = MinecraftCanvasRendererProbe.create()
        openedRenderers++
        return object : MinecraftCanvasRenderer {
            private var previousTime: FrameTime? = null
            private var closed = false

            override fun render(context: MinecraftCanvasContext): DrawImage {
                check(closed.not()) { "A retired changing-generation renderer was invoked." }
                check(previousTime != context.frameTime) { "A changing-generation renderer ran twice in one native presentation." }
                previousTime = context.frameTime
                rendererGeneration++
                physicalSize = context.physicalSize
                val phase = nextPaint
                lastPaint = phase
                nextPaint = if (phase == PaintPhase.Painted) PaintPhase.Clear else PaintPhase.Painted
                if (phase == PaintPhase.Painted) resources.render(context)
                probe.recordUse()
                val pixels =
                    createDrawImage(
                        context.physicalSize,
                        IntArray(context.physicalSize.width * context.physicalSize.height) { if (phase == PaintPhase.Painted) 0x8000FF00.toInt() else 0 },
                    )
                afterNativeRender?.invoke()
                return pixels
            }

            override fun close() {
                check(closed.not()) { "A changing-generation renderer was closed twice." }
                probe.close()
                closed = true
                closedRenderers++
            }
        }
    }

    override fun close() {
        afterNativeRender = null
        check(textureGeneration == releasedLeases) { "An external native texture still has changing-generation capture leases." }
        check(openedRenderers == closedRenderers) { "A changing-generation renderer outlived its native cleanup." }
        resources.close()
    }

    private enum class PaintPhase {
        Painted,
        Clear,
    }
}
