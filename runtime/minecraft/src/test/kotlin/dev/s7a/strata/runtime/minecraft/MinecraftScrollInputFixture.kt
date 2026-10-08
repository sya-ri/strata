package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollMetrics
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * Real retained profile area with two independently keyed linked bars and independent uniform pixel geometry.
 * Diagnostics observe bounded work only outside timing; caller state and input traces keep their own lifetime.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftScrollInputFixture(
    initialOffset: Double = 0.0,
    private val bars: Boolean = true,
    scrollRate: Int = 9,
) : AutoCloseable {
    /**
     * Caller-owned position shared by every declared control.
     */
    internal val state = ScrollState(initialOffset)

    /**
     * Prepared content size, mutated only through the public state/declaration boundary.
     */
    internal val extent = mutableStateOf(IntSize(80, 180))

    /**
     * Independent child paint state for the unrelated pending-dirtiness control.
     */
    internal val color = mutableStateOf(ArgbColor(0xffabcdef.toInt()))

    /**
     * Typed literal observer ordering trace; callbacks retain no node references.
     */
    internal val trace = ArrayList<Pair<Observer, ScrollMetrics>>()
    private val first = state.observe { metrics -> trace.add(Observer.First to metrics) }
    private val viewport = IntSize(if (bars) 120 else 100, 50)
    private val time = FrameTime(0)
    private val areaKey = ElementKey(Owner.Area)
    private val firstBarKey = ElementKey(Owner.FirstBar)
    private val secondBarKey = ElementKey(Owner.SecondBar)

    /**
     * Current host owns exactly this retained tree and its terminal cleanup.
     */
    internal val host =
        createMinecraftUiHost(
            UiDefinition("Scroll origin invalidation") {
                Row(spacing = 4) {
                    ScrollArea(state, modifier = Modifier.Empty.size(100, 50), key = areaKey, scrollRate = scrollRate) {
                        Spacer(modifier = Modifier.Empty.size(extent.value.width, extent.value.height).background(color.value))
                    }
                    if (bars) {
                        Scrollbar(state, Modifier.Empty.size(6, 50), firstBarKey)
                        Scrollbar(state, Modifier.Empty.size(6, 50), secondBarKey)
                    }
                }
            },
            MinecraftProfileFixture.create(),
        )
    private val last: AutoCloseable
    private var barInput: PointerInputNode? = null

    /**
     * Work monitor started only after the actual first frame and geometry publication.
     */
    internal val monitor: UiRenderMonitor

    init {
        host.attach()
        frame()
        last = state.observe { metrics -> trace.add(Observer.Last to metrics) }
        monitor = host.startRenderMonitoring(32)
        if (bars) barInput = findBar(field(field(field(field(host, "session"), "session"), "tree"), "root"))
        trace.clear()
    }

    /**
     * Returns the same explicit-time frame boundary for every input opportunity.
     */
    internal fun frame(): RuntimeUiFrame = host.frame(viewport, time)

    /**
     * Delivers one finite or exceptional public wheel request inside the area.
     */
    internal fun wheel(delta: Double): InputResult = host.dispatchPointer(PointerEvent.Scroll(IntOffset(20, 20), 0.0, delta))

    /**
     * Starts the first linked scrollbar drag flag through its real primary press; it has no capture capability.
     */
    internal fun press(): InputResult = host.dispatchPointer(PointerEvent.Press(IntOffset(106, 10), PointerButton.Primary))

    /**
     * Delivers public in-track input, or directly exercises the actual node SPI for unreachable outside-track branches.
     * The standalone scrollbar does not implement capture, so outside branch checks do not claim host capture support.
     */
    internal fun drag(
        y: Int,
        delta: Double = 0.0,
    ): InputResult {
        val event = PointerEvent.Drag(IntOffset(106, y), PointerButton.Primary, 0.0, delta)
        return if (y < 0 || 50 < y) checkNotNull(barInput).onPointerEvent(event, IntOffset(2, y)) else host.dispatchPointer(event)
    }

    /**
     * Actual standalone node capability, checked outside input and measurement.
     */
    internal val capturesPointer: Boolean
        get() = barInput is PointerCaptureNode

    /**
     * Ends the drag flag through the unchanged public in-track primary release.
     */
    internal fun release(y: Int = 10): InputResult = host.dispatchPointer(PointerEvent.Release(IntOffset(106, y), PointerButton.Primary))

    /**
     * Counts the exact keyed originating callback phase in the current bounded interval.
     */
    internal fun count(
        owner: Owner,
        metric: UiRenderMetric,
    ): Long {
        val key = when (owner) {
            Owner.Area -> areaKey
            Owner.FirstBar -> firstBarKey
            Owner.SecondBar -> secondBarKey
        }
        val id = monitor.findNodes(key).single()
        val snapshot = monitor.snapshot()
        check(snapshot.overflowed.not())
        return snapshot.nodes.single { node -> node.id == id }.counts[metric] ?: 0L
    }

    /**
     * Checks full bounded pixels at three densities using independently placed uniform area/child/bar rectangles.
     */
    internal fun verifyPixels(frame: RuntimeUiFrame = frame()) {
        val metrics = state.metrics
        val content = extent.value
        val top = 2 - metrics.offset.toInt()
        val reference = buildList {
            add(DrawCommand.FillRectangle(IntRect(0, 0, 100, 50), ArgbColor(0xff111111.toInt())))
            add(DrawCommand.PushClip(IntRect(0, 0, 100, 50)))
            add(DrawCommand.FillRectangle(IntRect((100 - content.width) / 2, top, (100 + content.width) / 2, top + content.height), color.value))
            add(DrawCommand.PopClip)
            if (bars && metrics.canScroll) {
                val height = (2500L / metrics.contentExtent).toInt().coerceIn(32, 42)
                val thumb = ((metrics.offset / metrics.maximumOffset) * (50 - height)).toInt().coerceAtLeast(0)
                for (x in listOf(104, 114)) {
                    add(DrawCommand.FillRectangle(IntRect(x, 0, x + 6, 50), ArgbColor(0xff141414.toInt())))
                    add(DrawCommand.FillRectangle(IntRect(x, thumb, x + 6, thumb + height), ArgbColor(0xff151515.toInt())))
                }
            }
        }
        for (scale in 1..3) {
            check(rasterizeHeadless(frame.drawCommands, viewport, scale).copyArgb().contentEquals(rasterizeHeadless(reference, viewport, scale).copyArgb()))
        }
    }

    /**
     * Releases every retained observer while preserving only detached diagnostics and caller state.
     */
    override fun close() {
        host.close()
        barInput = null
        monitor.close()
        first.close()
        last.close()
        val observers =
            ScrollState::class.java
                .getDeclaredField("observers")
                .apply { isAccessible = true }
                .get(state) as Map<*, *>
        check(observers.isEmpty())
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any =
        checkNotNull(
            owner.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(owner),
        )

    private fun findBar(entry: Any): PointerInputNode? {
        val node = field(entry, "node")
        if (node is PointerInputNode && node is PaintNode && (node is LayoutNode).not()) return node
        for (child in field(entry, "children") as List<*>) {
            findBar(checkNotNull(child))?.let { return it }
        }
        return null
    }

    /**
     * Component identities used for actual per-node work assertions.
     */
    internal enum class Owner {
        Area,
        FirstBar,
        SecondBar,
    }

    /**
     * Literal caller callback registration order around retained control attachment.
     */
    internal enum class Observer {
        First,
        Last,
    }
}
