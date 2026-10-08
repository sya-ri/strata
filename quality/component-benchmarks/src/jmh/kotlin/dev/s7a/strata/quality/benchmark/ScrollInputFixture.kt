package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
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
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.quality.benchmark.ScrollInputBenchmark.Action
import dev.s7a.strata.quality.benchmark.ScrollInputBenchmark.Case
import dev.s7a.strata.quality.benchmark.ScrollInputBenchmark.Endpoint
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

// Why: one current fixture owns preparation, restoring input cycles, untimed work/pixel oracles and terminal state release.

/**
 * Actual profile area with two independent linked bars, or an inner consuming area inside its unchanged parent.
 * All immutable events and geometry are prepared before sampling; diagnostics and caller trace observers are temporary.
 * Outside-track cases call the prebound actual scrollbar node SPI because this standalone node has no capture capability.
 */
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal class ScrollInputFixture(
    private val workload: Case,
) : AutoCloseable {
    private val area = ScrollState()
    private val inner = ScrollState()
    private val target = if (workload.nested) inner else area
    private val firstExtent = IntSize(80, 180)
    private val secondExtent = IntSize(80, 210)
    private val extent = mutableStateOf(firstExtent)
    private val firstColor = ArgbColor(0xffabcdef.toInt())
    private val secondColor = ArgbColor(0xff102030.toInt())
    private val color = mutableStateOf(firstColor)
    private val viewport = IntSize(if (workload.bars) 120 else 100, 50)
    private val time = FrameTime(0)
    private val direction = if (workload.endpoint === Endpoint.Top) 1.0 else -1.0
    private val wheelPoint = IntOffset(20, 20)
    private val wheelOutward = PointerEvent.Scroll(wheelPoint, 0.0, -direction)
    private val wheelZero = PointerEvent.Scroll(wheelPoint, 0.0, 0.0)
    private val wheelForward = PointerEvent.Scroll(wheelPoint, 0.0, direction)
    private val wheelReverse = PointerEvent.Scroll(wheelPoint, 0.0, -direction)
    private val wheelFraction = PointerEvent.Scroll(wheelPoint, 0.0, direction * 0.25)
    private val wheelFractionReverse = PointerEvent.Scroll(wheelPoint, 0.0, -direction * 0.25)
    private val wheelOverflow = PointerEvent.Scroll(wheelPoint, 0.0, Double.MAX_VALUE)
    private val barPoint = IntOffset(106, 20)
    private val press = PointerEvent.Press(IntOffset(106, 10), PointerButton.Primary)
    private val release = PointerEvent.Release(IntOffset(106, 10), PointerButton.Primary)
    private val barOutward = PointerEvent.Drag(barPoint, PointerButton.Primary, 0.0, -direction)
    private val barZero = PointerEvent.Drag(barPoint, PointerButton.Primary, 0.0, 0.0)
    private val barForward = PointerEvent.Drag(barPoint, PointerButton.Primary, 0.0, direction)
    private val barReverse = PointerEvent.Drag(barPoint, PointerButton.Primary, 0.0, -direction)
    private val barFraction = PointerEvent.Drag(barPoint, PointerButton.Primary, 0.0, direction * 0.25)
    private val barFractionReverse = PointerEvent.Drag(barPoint, PointerButton.Primary, 0.0, -direction * 0.25)
    private val outsideY = if (workload.endpoint === Endpoint.Top) -10 else 60
    private val barOutside = PointerEvent.Drag(IntOffset(106, outsideY), PointerButton.Primary, 0.0, 0.0)
    private val outsideLocal = IntOffset(2, outsideY)
    private val areaKey = ElementKey(Owner.Area)
    private val innerKey = ElementKey(Owner.Inner)
    private val firstBarKey = ElementKey(Owner.FirstBar)
    private val secondBarKey = ElementKey(Owner.SecondBar)
    private val profile = ScrollInputAssets.create()
    private val host =
        createMinecraftUiHost(
            UiDefinition("Scroll input opportunities") {
                Row(spacing = 4) {
                    ScrollArea(area, Modifier.Empty.size(100, 50), areaKey, scrollRate = if (workload.action === Action.Overflow) 9 else 1) {
                        if (workload.nested) {
                            Column {
                                ScrollArea(inner, Modifier.Empty.size(80, 50), innerKey, scrollRate = 1) {
                                    Spacer(modifier = Modifier.Empty.size(60, 180).background(color.value))
                                }
                                Spacer(modifier = Modifier.Empty.size(80, 130))
                            }
                        } else {
                            Spacer(modifier = Modifier.Empty.size(extent.value.width, extent.value.height).background(color.value))
                        }
                    }
                    if (workload.bars) {
                        Scrollbar(area, Modifier.Empty.size(6, 50), firstBarKey)
                        Scrollbar(area, Modifier.Empty.size(6, 50), secondBarKey)
                    }
                }
            },
            profile,
        )
    private var barInput: PointerInputNode? = null
    private var closed = false
    private val barAction =
        when (workload.action) {
            Action.BarOutward, Action.BarZero, Action.BarFractional, Action.BarOrdinary, Action.BarOutside -> true
            else -> false
        }

    init {
        try {
            host.attach()
            frame()
            if (workload.endpoint === Endpoint.Bottom) target.scrollTo(134.0)
            frame()
            if (barAction) {
                check(host.dispatchPointer(press) === InputResult.Consumed)
                frame()
                val session = TextAreaInputAccess.field(TextAreaInputAccess.field(host, "session"), "session")
                val root = TextAreaInputAccess.field(TextAreaInputAccess.field(session, "tree"), "root")
                barInput = checkNotNull(findBar(root))
            }
        } catch (failure: Throwable) {
            try {
                host.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * Executes the complete declared opportunity; restoring cycles count both directions and both frame settlements.
     * The exhaustive typed selection preserves each fixed case's declared boundary without a corpus-specific launcher branch.
     * No trace, work monitoring, reflection, reference rasterization or input construction occurs here.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun operation(): RuntimeUiFrame {
        when (workload.action) {
            Action.WheelOutward -> host.dispatchPointer(wheelOutward)
            Action.WheelZero -> host.dispatchPointer(wheelZero)
            Action.Overflow -> host.dispatchPointer(wheelOverflow)
            Action.CoalescedUnchanged -> repeat(4) { host.dispatchPointer(wheelOutward) }
            Action.WheelFractional -> pointerCycle(wheelFraction, wheelFractionReverse)
            Action.WheelOrdinary -> pointerCycle(wheelForward, wheelReverse)
            Action.CoalescedChanged -> {
                repeat(4) { host.dispatchPointer(wheelForward) }
                frame()
                repeat(4) { host.dispatchPointer(wheelReverse) }
            }
            Action.SignedZero -> {
                target.scrollTo(-0.0)
                frame()
                host.dispatchPointer(wheelZero)
            }
            Action.DirtyLeaf -> {
                color.value = secondColor
                host.dispatchPointer(wheelOutward)
                frame()
                color.value = firstColor
                host.dispatchPointer(wheelOutward)
            }
            Action.ExternalState -> {
                target.scrollTo(9.0)
                host.dispatchPointer(wheelZero)
                frame()
                target.scrollTo(0.0)
                host.dispatchPointer(wheelZero)
            }
            Action.Geometry -> {
                extent.value = secondExtent
                host.dispatchPointer(wheelZero)
                frame()
                extent.value = firstExtent
                host.dispatchPointer(wheelZero)
            }
            Action.BarOutward -> host.dispatchPointer(barOutward)
            Action.BarZero -> host.dispatchPointer(barZero)
            Action.BarOutside -> checkNotNull(barInput).onPointerEvent(barOutside, outsideLocal)
            Action.BarFractional -> pointerCycle(barFraction, barFractionReverse)
            Action.BarOrdinary -> pointerCycle(barForward, barReverse)
            Action.Clean -> Unit
        }
        return frame()
    }

    /**
     * Checks literal state/callback results and independent full pixels without requiring one optimization strategy on both archives.
     * Baseline/candidate origin callback attempts are printed as actual untimed evidence, not inferred from skipped paint or CPU scores.
     */
    internal fun verify() {
        val initial = frame()
        check(target.metrics == ScrollMetrics(if (workload.endpoint === Endpoint.Top) 0.0 else 134.0, 50, 184))
        check(initial.semantics.isEmpty())
        verifyPixels(initial)
        val noChange =
            when (workload.action) {
                Action.WheelOutward, Action.WheelZero, Action.Overflow, Action.CoalescedUnchanged,
                Action.BarOutward, Action.BarZero, Action.BarOutside, Action.Clean,
                -> true
                else -> false
            }
        val repetitions = if (noChange) 100 else 1
        val trace = ArrayList<Pair<Observer, ScrollMetrics>>()
        target.observe { metrics -> trace.add(Observer.First to metrics) }.use {
            target.observe { metrics -> trace.add(Observer.Last to metrics) }.use {
                host.startRenderMonitoring(64).use { monitor ->
                    repeat(repetitions) {
                        val next = operation()
                        check(next.drawCommands == initial.drawCommands)
                        check(next.semantics == initial.semantics)
                        check(target.metrics == ScrollMetrics(if (workload.endpoint === Endpoint.Top) 0.0 else 134.0, 50, 184))
                    }
                    check(trace == expectedTrace())
                    verifyCounts(monitor, noChange, repetitions)
                }
            }
        }
        verifyPixels(frame())
    }

    private fun verifyCounts(
        monitor: UiRenderMonitor,
        noChange: Boolean,
        repetitions: Int,
    ) {
        val origin = if (barAction) Owner.FirstBar else if (workload.nested) Owner.Inner else Owner.Area
        val layout = count(monitor, origin, UiRenderMetric.Layout)
        val paint = count(monitor, origin, UiRenderMetric.Paint)
        if (noChange) {
            val opportunities = if (workload.action === Action.Clean || workload.action === Action.Overflow) 0L else 100L
            val layouts = if (workload.action === Action.CoalescedUnchanged) 400L else opportunities
            check(layout == 0L || barAction.not() && layout == layouts)
            check(paint == 0L || paint == opportunities)
            if (barAction.not()) check((layout == 0L) == (paint == 0L))
        } else if (workload.action !== Action.DirtyLeaf) {
            check(0L < layout || barAction)
            check(0L < paint)
        }
        val linkedPaint = if (workload.bars) count(monitor, Owner.SecondBar, UiRenderMetric.Paint) else 0L
        val changes = if (noChange || workload.nested || workload.action === Action.DirtyLeaf) 0L else 2L
        check(linkedPaint == changes)
        check(monitor.snapshot().overflowed.not())
        println("ScrollInput " + workload.name + ": repetitions=" + repetitions + " origin=" + origin + " layout=" + layout + " paint=" + paint + " linkedPaint=" + linkedPaint)
    }

    private fun pointerCycle(
        first: PointerEvent,
        second: PointerEvent,
    ) {
        host.dispatchPointer(first)
        frame()
        host.dispatchPointer(second)
    }

    private fun frame(): RuntimeUiFrame = host.frame(viewport, time)

    private fun expectedTrace(): List<Pair<Observer, ScrollMetrics>> {
        val start = if (workload.endpoint === Endpoint.Top) 0.0 else 134.0
        val positions =
            when (workload.action) {
                Action.WheelFractional -> listOf(start + direction * 0.25, start)
                Action.WheelOrdinary -> listOf(start + direction, start)
                Action.BarFractional -> listOf(start + direction * 0.25 * (134.0 / 18.0), start)
                Action.BarOrdinary -> listOf(start + direction * (134.0 / 18.0), start)
                Action.CoalescedChanged -> listOf(1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0, 0.0)
                Action.SignedZero -> listOf(-0.0, 0.0)
                Action.ExternalState -> listOf(9.0, 0.0)
                else -> emptyList()
            }
        val snapshots =
            if (workload.action === Action.Geometry) {
                listOf(ScrollMetrics(0.0, 50, 214), ScrollMetrics(0.0, 50, 184))
            } else {
                positions.map { offset -> ScrollMetrics(offset, 50, 184) }
            }
        return snapshots.flatMap { metrics -> listOf(Observer.First to metrics, Observer.Last to metrics) }
    }

    private fun count(
        monitor: UiRenderMonitor,
        owner: Owner,
        metric: UiRenderMetric,
    ): Long {
        val key =
            when (owner) {
                Owner.Area -> areaKey
                Owner.Inner -> innerKey
                Owner.FirstBar -> firstBarKey
                Owner.SecondBar -> secondBarKey
            }
        val id = monitor.findNodes(key).single()
        return monitor
            .snapshot()
            .nodes
            .single { node -> node.id == id }
            .counts[metric] ?: 0L
    }

    private fun verifyPixels(frame: RuntimeUiFrame) {
        val outerTop = 2 - area.metrics.offset.toInt()
        val reference = buildList {
            add(DrawCommand.FillRectangle(IntRect(0, 0, 100, 50), ArgbColor(0xff111111.toInt())))
            add(DrawCommand.PushClip(IntRect(0, 0, 100, 50)))
            if (workload.nested) {
                add(DrawCommand.PushClip(IntRect(10, outerTop, 90, outerTop + 50)))
                val top = outerTop + 2 - inner.metrics.offset.toInt()
                add(DrawCommand.FillRectangle(IntRect(20, top, 80, top + 180), color.value))
                add(DrawCommand.PopClip)
            } else {
                add(DrawCommand.FillRectangle(IntRect(10, outerTop, 90, outerTop + extent.value.height), color.value))
            }
            add(DrawCommand.PopClip)
            if (workload.bars) {
                val thumbTop = ((area.metrics.offset / 134.0) * 18.0).toInt()
                for (x in listOf(104, 114)) {
                    add(DrawCommand.FillRectangle(IntRect(x, 0, x + 6, 50), ArgbColor(0xff141414.toInt())))
                    add(DrawCommand.FillRectangle(IntRect(x, thumbTop, x + 6, thumbTop + 32), ArgbColor(0xff151515.toInt())))
                }
                val actualThumbs =
                    frame.drawCommands
                        .filterIsInstance<DrawCommand.BlitImage>()
                        .filter { command -> command.image === ScrollInputAssets.thumb }
                check(actualThumbs.map { command -> command.destination } == listOf(IntRect(104, thumbTop, 110, thumbTop + 32), IntRect(114, thumbTop, 120, thumbTop + 32)))
            }
        }
        for (scale in 1..3) {
            check(rasterizeHeadless(frame.drawCommands, viewport, scale).copyArgb().contentEquals(rasterizeHeadless(reference, viewport, scale).copyArgb()))
        }
    }

    private fun findBar(entry: Any): PointerInputNode? {
        val node = TextAreaInputAccess.field(entry, "node")
        if (node is PointerInputNode && node is PaintNode && (node is LayoutNode).not()) return node
        for (child in TextAreaInputAccess.field(entry, "children") as List<*>) {
            findBar(checkNotNull(child))?.let { return it }
        }
        return null
    }

    /**
     * Releases the current drag/state ownership; immutable old frames remain detached and unchanged.
     */
    override fun close() {
        if (closed) return
        closed = true
        val owner = barInput
        val detached =
            try {
                val old = frame()
                val commands = old.drawCommands.toList()
                if (barAction) host.dispatchPointer(release)
                old to commands
            } finally {
                try {
                    host.close()
                } finally {
                    barInput = null
                }
            }
        check(detached.first.drawCommands == detached.second)
        check((TextAreaInputAccess.field(area, "observers") as Map<*, *>).isEmpty())
        check((TextAreaInputAccess.field(inner, "observers") as Map<*, *>).isEmpty())
        if (owner != null) {
            check(TextAreaInputAccess.optional(owner, "state") == null)
            check(TextAreaInputAccess.optional(owner, "observer") == null)
            check(TextAreaInputAccess.optional(owner, "background") == null)
            check(TextAreaInputAccess.optional(owner, "thumb") == null)
        }
    }

    /**
     * Stable identities of actual retained controls, independent of runtime class names.
     */
    private enum class Owner {
        Area,
        Inner,
        FirstBar,
        SecondBar,
    }

    /**
     * Literal external callback registration order.
     */
    private enum class Observer {
        First,
        Last,
    }
}
