package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import kotlin.math.floor

/**
 * Measures complete public pointer opportunities against one real retained core session.
 * A fixed matrix separates broad/deep sparse and dense input, clips/transforms, capture and cold/dirty/clean controls.
 * Events and immutable topology are prepared outside timing; initial rows include fresh nodes, attach/frame/input/close.
 * No invocation setup, private dispatcher, diagnostic counter, reflection or independent scan runs during collection.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PointerCapabilityBenchmark {
    /**
     * Delivers the declared opportunity or full restoring gesture/lifetime cycle.
     */
    @Benchmark
    public fun opportunity(scene: Scene): InputResult = scene.perform()

    /**
     * One current owner-thread tree and prebuilt input set per worker.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Complete typed corpus without independent Cartesian parameter dimensions.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.BroadNone1

        private lateinit var fixture: Fixture

        /**
         * Creates committed geometry and runs callback/pixel/lifetime oracles before measurement.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            val prepared = Fixture(workload)
            try {
                prepared.verifyWork()
                fixture = prepared
            } catch (failure: Throwable) {
                releaseAfterFailure(prepared, failure)
            }
        }

        /**
         * Uses the existing runtime input/frame boundary, including every declared restoration.
         */
        public fun perform(): InputResult = fixture.perform()

        /**
         * Releases the retained owner outside the ordinary input interval.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.closeVerified()
    }

    /**
     * Counts identify nonstructural entries; sparse broad trees have one participant per hundred entries.
     * Sparse deep trees contain only their deepest participant, so inert ancestry is still present.
     */
    public enum class Workload(
        internal val shape: Shape,
        internal val count: Int,
        internal val distribution: Distribution,
        internal val operation: Operation,
        internal val consumption: Consumption = Consumption.None,
        internal val transformed: Boolean = false,
        internal val outside: Boolean = false,
    ) {
        BroadNone1(Shape.Broad, 1, Distribution.None, Operation.Move),
        BroadNone128(Shape.Broad, 128, Distribution.None, Operation.Move),
        BroadNone10000(Shape.Broad, 10000, Distribution.None, Operation.Move),
        BroadSparse128Move(Shape.Broad, 128, Distribution.Sparse, Operation.Move),
        BroadSparse128Drag(Shape.Broad, 128, Distribution.Sparse, Operation.Drag),
        BroadSparse10000Move(Shape.Broad, 10000, Distribution.Sparse, Operation.Move),
        BroadSparse10000Drag(Shape.Broad, 10000, Distribution.Sparse, Operation.Drag),
        BroadSparse10000Scroll(Shape.Broad, 10000, Distribution.Sparse, Operation.Scroll),
        BroadSparse10000PressCycle(Shape.Broad, 10000, Distribution.Sparse, Operation.PressCycle),
        BroadDense128Move(Shape.Broad, 128, Distribution.Dense, Operation.Move),
        BroadDense128Drag(Shape.Broad, 128, Distribution.Dense, Operation.Drag),
        BroadDense10000Move(Shape.Broad, 10000, Distribution.Dense, Operation.Move),
        BroadDense10000Drag(Shape.Broad, 10000, Distribution.Dense, Operation.Drag),
        BroadDense10000Scroll(Shape.Broad, 10000, Distribution.Dense, Operation.Scroll),
        BroadDense10000PressCycle(Shape.Broad, 10000, Distribution.Dense, Operation.PressCycle),
        DeepSparse32Move(Shape.Deep, 32, Distribution.Sparse, Operation.Move),
        DeepSparse128Move(Shape.Deep, 128, Distribution.Sparse, Operation.Move),
        DeepSparse128Drag(Shape.Deep, 128, Distribution.Sparse, Operation.Drag),
        DeepDense32Move(Shape.Deep, 32, Distribution.Dense, Operation.Move),
        DeepDense128Move(Shape.Deep, 128, Distribution.Dense, Operation.Move),
        DeepDense128Drag(Shape.Deep, 128, Distribution.Dense, Operation.Drag),
        OverflowMove(Shape.Overflow, 1, Distribution.Dense, Operation.Move),
        OverflowDrag(Shape.Overflow, 1, Distribution.Dense, Operation.Drag),
        OverflowClippedMove(Shape.OverflowClipped, 1, Distribution.Dense, Operation.Move),
        OverflowClippedDrag(Shape.OverflowClipped, 1, Distribution.Dense, Operation.Drag),
        NestedClipInsideMove(Shape.NestedClip, 1, Distribution.Dense, Operation.Move),
        NestedClipOutsideMove(Shape.NestedClip, 1, Distribution.Dense, Operation.Move, outside = true),
        TransformedDeepMove(Shape.Deep, 32, Distribution.Sparse, Operation.Move, transformed = true),
        TransformedDeepDrag(Shape.Deep, 32, Distribution.Sparse, Operation.Drag, transformed = true),
        TransformedOverflowMove(Shape.Overflow, 1, Distribution.Dense, Operation.Move, transformed = true),
        TransformedOverflowDrag(Shape.Overflow, 1, Distribution.Dense, Operation.Drag, transformed = true),
        BroadTopmostConsumed(Shape.Broad, 10000, Distribution.Sparse, Operation.Move, consumption = Consumption.Topmost),
        BroadLastConsumed(Shape.Broad, 10000, Distribution.Sparse, Operation.Move, consumption = Consumption.Last),
        CapturedOutsideCycle(Shape.Broad, 10000, Distribution.Sparse, Operation.CaptureCycle, consumption = Consumption.Capture),
        GeometrySparse10000(Shape.Broad, 10000, Distribution.Sparse, Operation.Geometry),
        GeometryDense10000(Shape.Broad, 10000, Distribution.Dense, Operation.Geometry),
        DispatchInvalidationSparse(Shape.Broad, 10000, Distribution.Sparse, Operation.DispatchInvalidation),
        DispatchInvalidationDense(Shape.Broad, 10000, Distribution.Dense, Operation.DispatchInvalidation),
        PaintSparse10000(Shape.Broad, 10000, Distribution.Sparse, Operation.Paint),
        PaintDense10000(Shape.Broad, 10000, Distribution.Dense, Operation.Paint),
        DetachSparse10000(Shape.Broad, 10000, Distribution.Sparse, Operation.Detach),
        DetachDense10000(Shape.Broad, 10000, Distribution.Dense, Operation.Detach),
        InitialSparse10000(Shape.Broad, 10000, Distribution.Sparse, Operation.Initial),
        InitialDense10000(Shape.Broad, 10000, Distribution.Dense, Operation.Initial),
        CleanSparse10000(Shape.Broad, 10000, Distribution.Sparse, Operation.Clean),
        CleanDense10000(Shape.Broad, 10000, Distribution.Dense, Operation.Clean),
    }

    /**
     * Immutable topology selection, independent of capability density and input protocol.
     */
    internal enum class Shape { Broad, Deep, Overflow, OverflowClipped, NestedClip }

    /**
     * No participant, explicit sparse membership, or every nonstructural entry.
     */
    internal enum class Distribution { None, Sparse, Dense }

    /**
     * Input rows have no frame work; dirty rows include synchronization and the completed frame.
     * Capture/press rows include release, detach includes attach/frame/input, and initial owns a fresh full lifetime.
     */
    internal enum class Operation { Move, Drag, Scroll, PressCycle, CaptureCycle, Geometry, DispatchInvalidation, Paint, Detach, Initial, Clean }

    /**
     * Ignored traversal, topmost/last eligible consumption, or press-only capture followed by outside delivery.
     */
    internal enum class Consumption { None, Topmost, Last, Capture }

    private enum class Role { Root, Passive, Participant }

    private class Specification(
        val id: Int,
        val role: Role,
        val size: Int = 10,
        val offset: IntOffset = IntOffset.Zero,
        val clipped: Boolean = false,
        val children: List<Specification> = emptyList(),
    )

    private class Events(position: IntOffset) {
        val move = PointerEvent.Move(position)
        val drag = PointerEvent.Drag(position, PointerButton.Primary, 8.0, 12.0)
        val scroll = PointerEvent.Scroll(position, 0.25, -0.5)
        val press = PointerEvent.Press(position, PointerButton.Primary)
        val release = PointerEvent.Release(position, PointerButton.Primary)
        val outsideDrag = PointerEvent.Drag(IntOffset(60, 70), PointerButton.Primary, 8.0, 12.0)
        val outsideRelease = PointerEvent.Release(IntOffset(60, 70), PointerButton.Primary)
    }

    private class Fixture(
        private val workload: Workload,
        private val specification: Specification = specification(workload),
        private val events: Events = events(workload),
        recording: Boolean = false,
        private val topmost: Int? = participantIds(specification).maxOrNull(),
        private val last: Int? = participantIds(specification).minOrNull(),
    ) : AutoCloseable {
        private val counters = Counters(recording)
        private val nodes = ArrayList<BaseNode>()
        private val session: RuntimeUiSession
        private val root: RootNode
        private val initial: RuntimeUiFrame
        private var closed = false

        init {
            val element = element(specification)
            root = element.node as RootNode
            counters.invalidate = root::invalidateGeometry
            val modifier = if (workload.transformed) Modifier.Empty.scaleToFit(IntSize(10, 10), allowUpscaling = true) else Modifier.Empty
            val description = Description(element.node, element.children, modifier)
            session = createRuntimeUiSession { description }
            try {
                session.attach()
                initial = session.frame(constraints)
            } catch (failure: Throwable) {
                try {
                    session.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        fun perform(): InputResult =
            when (workload.operation) {
                Operation.Move -> {
                    session.dispatchPointer(events.move)
                }

                Operation.Drag -> {
                    session.dispatchPointer(events.drag)
                }

                Operation.Scroll -> {
                    session.dispatchPointer(events.scroll)
                }

                Operation.PressCycle -> {
                    session.dispatchPointer(events.press)
                    session.dispatchPointer(events.release)
                }

                Operation.CaptureCycle -> {
                    session.dispatchPointer(events.press)
                    session.dispatchPointer(events.outsideDrag)
                    session.dispatchPointer(events.outsideRelease)
                }

                Operation.Geometry -> {
                    root.invalidateGeometry()
                    val result = session.dispatchPointer(events.move)
                    session.frame(constraints)
                    result
                }

                Operation.DispatchInvalidation -> {
                    counters.invalidateNext = true
                    session.dispatchPointer(events.move)
                    val result = session.dispatchPointer(events.move)
                    session.frame(constraints)
                    result
                }

                Operation.Paint -> {
                    root.invalidatePresentation()
                    val result = session.dispatchPointer(events.move)
                    session.frame(constraints)
                    result
                }

                Operation.Detach -> {
                    session.detach()
                    session.attach()
                    session.frame(constraints)
                    session.dispatchPointer(events.move)
                }

                Operation.Initial -> {
                    Fixture(workload, specification, events, topmost = topmost, last = last).use { fresh -> fresh.session.dispatchPointer(events.move) }
                }

                Operation.Clean -> {
                    session.frame(constraints)
                    InputResult.Ignored
                }
            }

        fun verifyWork() {
            verifyFrame(initial)
            val oracle = OriginalPointer(specification, workload, topmost, last)
            val repeats =
                when (workload.operation) {
                    Operation.Move, Operation.Drag -> 100
                    Operation.Initial -> 2
                    else -> 8
                }
            counters.record = true
            repeat(repeats) {
                if (workload.operation === Operation.Initial) {
                    Fixture(workload, specification, events, recording = true, topmost = topmost, last = last).use { fresh ->
                        check(fresh.counters.measures == nodes.size && fresh.counters.layouts == nodes.size)
                        fresh.verifyEvents(listOf(events.move), oracle)
                        fresh.verifyFrame(fresh.initial)
                    }
                } else {
                    counters.reset()
                    oracle.trace.clear()
                    if (workload.operation === Operation.Detach) oracle.clearHover()
                    val sequence = sequence()
                    val expected = sequence.map(oracle::dispatch).lastOrNull() ?: InputResult.Ignored
                    check(perform() === expected)
                    check(counters.trace == oracle.trace)
                    verifyPhases()
                    verifyCurrentFrame()
                }
            }
            println("pointer=" + workload + ",callbacks=" + counters.trace.size + ",measure=" + counters.measures + ",layout=" + counters.layouts + ",paint=" + counters.paints + ",semantics=" + counters.semantics)
            counters.record = false
            counters.reset()
        }

        private fun verifyPhases() {
            when (workload.operation) {
                Operation.Geometry, Operation.DispatchInvalidation -> {
                    check(counters.measures == 0 && counters.layouts == 1)
                }

                Operation.Paint -> {
                    check(counters.measures == 0 && counters.layouts == 0 && counters.paints == 1 && counters.semantics == 1)
                }

                Operation.Detach -> {
                    Unit
                }

                Operation.Move, Operation.Drag, Operation.Scroll, Operation.PressCycle, Operation.CaptureCycle, Operation.Clean -> {
                    check(counters.measures == 0 && counters.layouts == 0 && counters.paints == 0 && counters.semantics == 0)
                }

                Operation.Initial -> {
                    error("Initial lifetime verified separately")
                }
            }
        }

        private fun verifyCurrentFrame() {
            val frame = session.frame(constraints)
            check(frame.drawCommands == initial.drawCommands && frame.semantics == initial.semantics)
            val unchanged =
                when (workload.operation) {
                    Operation.Move, Operation.Drag, Operation.Scroll, Operation.PressCycle, Operation.CaptureCycle, Operation.Clean -> true
                    Operation.Geometry, Operation.DispatchInvalidation, Operation.Paint, Operation.Detach, Operation.Initial -> false
                }
            if (unchanged) check(frame === initial)
            check(session.frame(constraints) === frame)
            verifyFrame(frame)
        }

        private fun verifyEvents(sequence: List<PointerEvent>, oracle: OriginalPointer) {
            counters.trace.clear()
            oracle.trace.clear()
            sequence.forEach { event -> check(session.dispatchPointer(event) === oracle.dispatch(event)) }
            check(counters.trace == oracle.trace)
        }

        private fun sequence(): List<PointerEvent> =
            when (workload.operation) {
                Operation.Move, Operation.Geometry, Operation.Paint, Operation.Detach, Operation.Initial -> listOf(events.move)
                Operation.Drag -> listOf(events.drag)
                Operation.Scroll -> listOf(events.scroll)
                Operation.PressCycle -> listOf(events.press, events.release)
                Operation.CaptureCycle -> listOf(events.press, events.outsideDrag, events.outsideRelease)
                Operation.DispatchInvalidation -> listOf(events.move, events.move)
                Operation.Clean -> emptyList()
            }

        private fun verifyFrame(frame: RuntimeUiFrame) {
            check(frame.size == IntSize(20, 20))
            check(frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>() == listOf(DrawCommand.FillRectangle(IntRect(0, 0, 20, 20), color)))
            check(frame.semantics == listOf(SemanticsEntry(IntRect(0, 0, 20, 20), referenceSemantics)))
            for (density in 1..3) {
                val image = rasterizeHeadless(frame.drawCommands, frame.size, density)
                check(image.copyArgb().all { it == color.value })
            }
        }

        private fun element(spec: Specification): Description {
            val node =
                when (spec.role) {
                    Role.Root -> {
                        if (spec.clipped) ClippedRoot(spec, counters) else RootNode(spec, counters)
                    }

                    Role.Passive -> {
                        if (spec.clipped) ClippedNode(spec, counters) else BaseNode(spec, counters)
                    }

                    Role.Participant -> {
                        PointerNode(spec, counters, workload, topmost, last)
                    }
                }
            nodes += node
            return Description(node, spec.children.map(::element))
        }

        fun closeVerified() {
            counters.record = true
            close()
        }

        override fun close() {
            if (closed) return
            closed = true
            val expected = nodes.size
            try {
                session.close()
            } finally {
                nodes.clear()
                counters.invalidate = {}
                counters.trace.clear()
            }
            if (counters.record) {
                check(counters.disposals == expected)
                check(initial.drawCommands.filterIsInstance<DrawCommand.FillRectangle>() == listOf(DrawCommand.FillRectangle(IntRect(0, 0, 20, 20), color)))
            }
        }
    }

    private class Counters(var record: Boolean) {
        var invalidate: () -> Unit = {}
        var invalidateNext = false
        var measures = 0
        var layouts = 0
        var paints = 0
        var semantics = 0
        var disposals = 0
        val trace = ArrayList<Trace>()

        fun reset() {
            measures = 0
            layouts = 0
            paints = 0
            semantics = 0
            trace.clear()
        }
    }

    private open class BaseNode(
        protected val specification: Specification,
        protected val counters: Counters,
    ) : Node(), MeasureNode, LayoutNode, LifecycleNode {
        override fun measure(scope: MeasureScope, constraints: Constraints): IntSize {
            if (counters.record) counters.measures += 1
            repeat(scope.childCount) { scope.measureChild(it, childConstraints) }
            return constraints.constrain(IntSize(specification.size, specification.size))
        }

        override fun layout(scope: LayoutScope) {
            if (counters.record) counters.layouts += 1
            repeat(scope.childCount) { index -> scope.placeChild(index, specification.children[index].offset) }
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            if (counters.record) counters.disposals += 1
        }
    }

    private class ClippedNode(
        spec: Specification,
        counters: Counters,
    ) : BaseNode(spec, counters), ClipChildrenNode

    private open class RootNode(
        spec: Specification,
        counters: Counters,
    ) : BaseNode(spec, counters), PaintNode, SemanticsNode {
        fun invalidateGeometry() = invalidate(DirtyMask.of(DirtyPhase.Layout))

        fun invalidatePresentation() = invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics))

        override fun paint(scope: PaintScope) {
            if (counters.record) counters.paints += 1
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }

        override fun semantics(scope: SemanticsScope) {
            if (counters.record) counters.semantics += 1
            scope.emit(referenceSemantics)
        }
    }

    private class ClippedRoot(
        spec: Specification,
        counters: Counters,
    ) : RootNode(spec, counters), ClipChildrenNode

    private class PointerNode(
        spec: Specification,
        counters: Counters,
        private val workload: Workload,
        private val topmost: Int?,
        private val last: Int?,
    ) : BaseNode(spec, counters), PointerCaptureNode, PointerHoverNode {
        override fun onPointerEvent(event: PointerEvent, localPosition: IntOffset): InputResult {
            if (counters.record) counters.trace += Trace.Input(specification.id, event, localPosition)
            if (counters.invalidateNext) {
                counters.invalidateNext = false
                counters.invalidate()
            }
            return consumption(workload, specification.id, topmost, last, event)
        }

        override fun onPointerHover(hovered: Boolean) {
            if (counters.record) counters.trace += Trace.Hover(specification.id, hovered)
        }

        override fun onPointerCaptureAcquired(button: PointerButton) {
            if (counters.record) counters.trace += Trace.Acquired(specification.id, button)
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            if (counters.record) counters.trace += Trace.Cancelled(specification.id, button)
        }
    }

    private class Description(
        val node: BaseNode,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
    ) : Element(ElementIdentity.Positional, descriptionType, children, modifier)

    private sealed interface Trace {
        data class Input(
            val id: Int,
            val event: PointerEvent,
            val local: IntOffset,
        ) : Trace

        data class Hover(
            val id: Int,
            val hovered: Boolean,
        ) : Trace

        data class Acquired(
            val id: Int,
            val button: PointerButton,
        ) : Trace

        data class Cancelled(
            val id: Int,
            val button: PointerButton,
        ) : Trace
    }

    /**
     * Independent old full-tree recursion uses immutable source topology and scalar coordinate arithmetic.
     * It never reads candidate entries, transforms, contains helpers or retained geometry.
     */
    private class OriginalPointer(
        private val root: Specification,
        private val workload: Workload,
        private val topmost: Int?,
        private val last: Int?,
    ) {
        val trace = ArrayList<Trace>()
        private var capture: Hit? = null
        private var button: PointerButton? = null
        private val scale = if (workload.transformed) 2.0 else 1.0

        fun dispatch(event: PointerEvent): InputResult {
            if (event is PointerEvent.Move || event is PointerEvent.Drag) hover(root, event.position, 0.0, 0.0, true)
            val current = capture
            val accepts =
                when (event) {
                    is PointerEvent.Move -> true
                    is PointerEvent.Drag -> event.button == button
                    is PointerEvent.Release -> event.button == button
                    is PointerEvent.Press, is PointerEvent.Scroll -> false
                }
            if (current != null && accepts) {
                if (event is PointerEvent.Release) {
                    capture = null
                    button = null
                }
                deliver(current, event)
                return InputResult.Consumed
            }
            return visit(root, event, 0.0, 0.0, true)
        }

        fun clearHover() = reset(root)

        private fun reset(spec: Specification) {
            spec.children.asReversed().forEach(::reset)
            if (spec.role === Role.Participant) trace += Trace.Hover(spec.id, false)
        }

        private fun hover(spec: Specification, position: IntOffset, x: Double, y: Double, allowed: Boolean) {
            val descendants = allowed && (spec.clipped.not() || contains(spec, position, x, y))
            for (child in spec.children.asReversed()) hover(child, position, x + child.offset.x * scale, y + child.offset.y * scale, descendants)
            if (spec.role === Role.Participant) trace += Trace.Hover(spec.id, allowed && contains(spec, position, x, y))
        }

        private fun visit(spec: Specification, event: PointerEvent, x: Double, y: Double, allowed: Boolean): InputResult {
            val descendants = allowed && (spec.clipped.not() || contains(spec, event.position, x, y))
            if (descendants) {
                for (child in spec.children.asReversed()) {
                    val result = visit(child, event, x + child.offset.x * scale, y + child.offset.y * scale, true)
                    if (result === InputResult.Consumed) return result
                }
            }
            if (allowed && spec.role === Role.Participant && contains(spec, event.position, x, y)) {
                val hit = Hit(spec, x, y)
                val result = deliver(hit, event)
                if (result === InputResult.Consumed && event is PointerEvent.Press && capture == null) {
                    capture = hit
                    button = event.button
                    trace += Trace.Acquired(spec.id, event.button)
                }
                return result
            }
            return InputResult.Ignored
        }

        private fun deliver(hit: Hit, event: PointerEvent): InputResult {
            val local = IntOffset(floor((event.position.x - hit.x) / scale).toInt(), floor((event.position.y - hit.y) / scale).toInt())
            val delivered = if (event is PointerEvent.Drag && scale != 1.0) PointerEvent.Drag(event.position, event.button, event.deltaX / scale, event.deltaY / scale) else event
            trace += Trace.Input(hit.specification.id, delivered, local)
            return when (workload.consumption) {
                Consumption.None -> InputResult.Ignored
                Consumption.Topmost -> if (hit.specification.id == topmost) InputResult.Consumed else InputResult.Ignored
                Consumption.Last -> if (hit.specification.id == last) InputResult.Consumed else InputResult.Ignored
                Consumption.Capture -> if (hit.specification.id == topmost && event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
            }
        }

        private fun contains(spec: Specification, position: IntOffset, x: Double, y: Double): Boolean =
            x <= position.x && position.x < x + spec.size * scale && y <= position.y && position.y < y + spec.size * scale

        private class Hit(
            val specification: Specification,
            val x: Double,
            val y: Double,
        )
    }

    /**
     * Generic generated-fixture discovery invokes this complete untimed acceptance before collection.
     */
    public companion object {
        private val constraints = Constraints.fixed(20, 20)
        private val childConstraints = Constraints(maxWidth = 10, maxHeight = 10)
        private val color = ArgbColor(0xFF123456.toInt())
        private val referenceSemantics = Semantics(label = UiText.literal("pointer fixture"))
        private val descriptionType =
            ElementType(
                elementClass = Description::class,
                nodeClass = BaseNode::class,
                validateLocal = {},
                createNode = Description::node,
                updateNode = { _, _, _ -> DirtyMask.None },
            )

        /**
         * Requires every declared generated row, old-recursion traces, current pixels and exact terminal disposal.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(PointerCapabilityBenchmark::class.java), setOf("avgt")).size == 46)
            Workload.entries.forEach { workload ->
                val fixture = Fixture(workload)
                try {
                    fixture.verifyWork()
                } catch (failure: Throwable) {
                    releaseAfterFailure(fixture, failure)
                }
                fixture.closeVerified()
            }
        }

        private fun releaseAfterFailure(fixture: Fixture, failure: Throwable): Nothing {
            try {
                fixture.closeVerified()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }

        private fun consumption(workload: Workload, id: Int, topmost: Int?, last: Int?, event: PointerEvent): InputResult =
            when (workload.consumption) {
                Consumption.None -> InputResult.Ignored
                Consumption.Topmost -> if (id == topmost) InputResult.Consumed else InputResult.Ignored
                Consumption.Last -> if (id == last) InputResult.Consumed else InputResult.Ignored
                Consumption.Capture -> if (id == topmost && event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
            }

        private fun participantIds(spec: Specification): List<Int> =
            buildList {
                if (spec.role === Role.Participant) add(spec.id)
                spec.children.forEach { addAll(participantIds(it)) }
            }

        private fun events(workload: Workload): Events {
            val logical =
                when (workload.shape) {
                    Shape.Broad, Shape.Deep -> 6
                    Shape.Overflow, Shape.OverflowClipped -> 22
                    Shape.NestedClip -> if (workload.outside) 9 else 6
                }
            val position = logical * if (workload.transformed) 2 else 1
            return Events(IntOffset(position, position))
        }

        private fun specification(workload: Workload): Specification {
            val children =
                when (workload.shape) {
                    Shape.Broad -> {
                        List(workload.count) { id ->
                            val participant = workload.distribution === Distribution.Dense || workload.distribution === Distribution.Sparse && id % 100 == 0
                            Specification(id, if (participant) Role.Participant else Role.Passive)
                        }
                    }

                    Shape.Deep -> {
                        var descendants = emptyList<Specification>()
                        for (id in (0 until workload.count).reversed()) {
                            val participant = workload.distribution === Distribution.Dense || workload.distribution === Distribution.Sparse && id == workload.count - 1
                            descendants = listOf(Specification(id, if (participant) Role.Participant else Role.Passive, children = descendants))
                        }
                        descendants
                    }

                    Shape.Overflow, Shape.OverflowClipped -> {
                        listOf(Specification(0, Role.Passive, size = 1, offset = IntOffset(16, 16), children = listOf(Specification(1, Role.Participant))))
                    }

                    Shape.NestedClip -> {
                        val leaf = Specification(3, Role.Participant)
                        val inert = Specification(2, Role.Passive, size = 1, children = listOf(leaf))
                        val inner = Specification(1, Role.Passive, size = 4, offset = IntOffset(2, 2), clipped = true, children = listOf(inert))
                        listOf(Specification(0, Role.Passive, size = 8, offset = IntOffset(2, 2), clipped = true, children = listOf(inner)))
                    }
                }
            return Specification(-1, Role.Root, size = if (workload.transformed) 10 else 20, clipped = workload.shape === Shape.OverflowClipped || workload.shape === Shape.NestedClip, children = children)
        }
    }
}
