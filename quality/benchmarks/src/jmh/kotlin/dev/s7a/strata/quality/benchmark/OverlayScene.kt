package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderSnapshot
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Bounded worker-owned scene with one changing opaque background and immutable full-area translucent siblings.
 * Only the current frame, source revision, and optional 64-frame diagnostic interval are retained.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class OverlayScene(
    width: Int,
    private val layers: Int,
    monitoring: Boolean,
) : AutoCloseable {
    private val size = IntSize(width, width * 9 / 16)
    private val constraints = Constraints.fixed(size.width, size.height)
    private val source = BenchmarkStateSource(ArgbColor(0xFF102030.toInt()))
    private val session =
        createRuntimeUiSession {
            evaluateComponentTree {
                Stack(Modifier.Empty.size(size.width, size.height)) {
                    Observe(source) { color -> Spacer(Modifier.Empty.size(size.width, size.height).background(color)) }
                    repeat(layers) { index ->
                        Spacer(Modifier.Empty.size(size.width, size.height).background(ArgbColor(0x10FFFFFF)), key = ElementKey(index))
                    }
                }
            }
        }
    private val monitor: UiRenderMonitor?
    private var revision = 0

    init {
        require(0 < width && 0 < layers)
        session.attach()
        session.frame(constraints)
        monitor = if (monitoring) session.startRenderMonitoring() else null
    }

    /**
     * Publishes a new lower color and returns the next frame, checkpointing every 64 updates.
     */
    fun nextFrame(): RuntimeUiFrame {
        if (revision % 64 == 0) monitor?.checkpoint()
        revision += 1
        source.publish(ArgbColor(if (revision % 2 == 0) 0xFF102030.toInt() else 0xFF304050.toInt()))
        return session.frame(constraints)
    }

    /**
     * Fully recomposes the next changed frame; headless images never remain in this scene.
     */
    fun rasterizeNext(): HeadlessImage {
        val frame = nextFrame()
        return rasterizeHeadless(frame.drawCommands, size)
    }

    /**
     * Returns detached bounded evidence; monitoring must be enabled by the owner.
     */
    fun snapshot(): UiRenderSnapshot = checkNotNull(monitor).snapshot()

    /**
     * Independently computes the uniform expected pixel with integer opaque-destination source-over.
     */
    fun expectedPixel(): Int {
        var pixel = if (revision % 2 == 0) 0xFF102030.toInt() else 0xFF304050.toInt()
        repeat(layers) {
            var next = 0xFF000000.toInt()
            listOf(0, 8, 16).forEach { shift ->
                val channel = (pixel ushr shift) and 255
                next = next or (((255 * 16 + channel * 239 + 127) / 255) shl shift)
            }
            pixel = next
        }
        return pixel
    }

    override fun close() {
        try {
            monitor?.close()
        } finally {
            session.close()
        }
        check(source.subscribed.not()) { "The overlay scene retained its source after close." }
    }
}
