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
 * Tracks external-source leases and per-attachment renderers in a loaded native Canvas scene.
 *
 * The fixture and all counters belong to the client thread, not to a screen or immutable runtime frame.
 * Source ownership stays external; close is legal only after every lease and renderer has been released by real GPU fences.
 * Initial literal screenshot assertions supply no snapshots; a separate explicit mode tests same-generation portable capture.
 * Every renderer also owns an independently uploaded native resource whose real last-use fence must signal before close.
 */
internal class MinecraftCanvasTestFixture(
    private val resources: MinecraftCanvasTestResources,
) : AutoCloseable {
    /**
     * Immutable native input checks supplied by this adapter's fixture, detached from any native resource ownership.
     */
    internal val inputValidation: List<MinecraftCanvasInputValidation> = resources.inputValidation.toList()

    /**
     * Actual selected native backend for explicit environment assertions.
     */
    internal val backend: MinecraftCanvasTestBackend = resources.backend

    /**
     * Owner-thread capture mode; changes affect only newly captured generations.
     */
    internal var snapshotMode: MinecraftCanvasSnapshotMode = MinecraftCanvasSnapshotMode.Missing

    /**
     * Optional client-thread test callback after native render work; cleared before fixture ownership ends.
     */
    internal var afterNativeRender: (() -> Unit)? = null

    /**
     * Native device and driver metadata recorded with each full-frame screenshot.
     */
    internal val backendDescription: String = resources.backendDescription

    /**
     * Number of native leases acquired from the externally owned source.
     */
    internal var leasesOpened: Int = 0
        private set

    /**
     * Number of acquired leases released after native capture completion.
     */
    internal var leasesClosed: Int = 0
        private set

    /**
     * Number of independently created renderer attachments.
     */
    internal var renderersOpened: Int = 0
        private set

    /**
     * Number of renderer instances released after their final GPU use.
     */
    internal var renderersClosed: Int = 0
        private set

    /**
     * Number of actual custom-renderer callbacks, excluding declaration, layout, and cached paint.
     */
    internal var renderCalls: Int = 0
        private set

    /**
     * Most recent custom callback's logical destination.
     */
    internal var logicalSize: IntSize = IntSize.Zero
        private set

    /**
     * Most recent custom callback's physical target extent.
     */
    internal var physicalSize: IntSize = IntSize.Zero
        private set

    /**
     * Shared top-origin external source attached to multiple independent canvases.
     */
    internal val textureSource: CanvasSource = textureSource(MinecraftCanvasTextureOrigin.TopLeft)

    /**
     * The same native image normalized from a bottom-origin lease.
     */
    internal val flippedTextureSource: CanvasSource = textureSource(MinecraftCanvasTextureOrigin.BottomLeft)

    /**
     * Shared factory creating separate depth-enabled custom renderers for each attachment.
     */
    internal val rendererSource: CanvasSource = canvasSource(depth = true) { renderer(transparent = false) }

    /**
     * Transparent offscreen output proving a newly cleared target never substitutes unrelated pixels.
     */
    internal val transparentSource: CanvasSource = canvasSource { renderer(transparent = true) }

    private fun textureSource(origin: MinecraftCanvasTextureOrigin): CanvasSource =
        canvasSource(
            MinecraftCanvasTextureProvider {
                val lease = resources.lease(origin)
                check(lease.snapshot == null) { "Native acceptance must not supply a CPU snapshot." }
                leasesOpened++
                object : MinecraftCanvasTextureLease by lease {
                    override val snapshot: DrawImage? =
                        when (snapshotMode) {
                            MinecraftCanvasSnapshotMode.Missing -> {
                                null
                            }

                            MinecraftCanvasSnapshotMode.Matching -> {
                                createDrawImage(
                                    IntSize(2, 2),
                                    intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt()),
                                )
                            }
                        }

                    private var closed = false

                    override fun close() {
                        check(closed.not()) { "A captured texture lease was released twice." }
                        lease.close()
                        closed = true
                        leasesClosed++
                    }
                }
            },
        )

    private fun renderer(transparent: Boolean): MinecraftCanvasRenderer {
        val probe = MinecraftCanvasRendererProbe.create()
        renderersOpened++
        return object : MinecraftCanvasRenderer {
            private var previousTime: FrameTime? = null
            private var borrowed: MinecraftCanvasContext? = null
            private var closed = false

            override fun render(context: MinecraftCanvasContext): DrawImage? {
                check(closed.not()) { "A released renderer must never run again." }
                check(previousTime != context.frameTime) { "One attachment rendered twice in the same native presentation." }
                previousTime = context.frameTime
                borrowed?.let { expired ->
                    check(runCatching { expired.target }.isFailure) { "The previous Canvas callback context must expire." }
                }
                borrowed = context
                renderCalls++
                if (transparent.not()) {
                    check(context.target.useDepth) { "A depth-enabled factory must receive a depth target." }
                    logicalSize = context.logicalSize
                    physicalSize = context.physicalSize
                    resources.render(context)
                }
                probe.recordUse()
                afterNativeRender?.invoke()
                return when (snapshotMode) {
                    MinecraftCanvasSnapshotMode.Missing -> {
                        null
                    }

                    MinecraftCanvasSnapshotMode.Matching -> {
                        createDrawImage(
                            context.physicalSize,
                            IntArray(context.physicalSize.width * context.physicalSize.height) { if (transparent) 0 else 0x8000FF00.toInt() },
                        )
                    }
                }
            }

            override fun close() {
                check(closed.not()) { "A renderer instance was released twice." }
                borrowed?.let { expired ->
                    check(runCatching { expired.target }.isFailure) { "The last Canvas callback context must expire." }
                }
                borrowed = null
                probe.close()
                closed = true
                renderersClosed++
            }
        }
    }

    override fun close() {
        afterNativeRender = null
        check(leasesOpened == leasesClosed) { "The externally owned source still has live GPU capture leases." }
        check(renderersOpened == renderersClosed) { "Custom renderers outlived their final screen cleanup." }
        resources.close()
    }
}
