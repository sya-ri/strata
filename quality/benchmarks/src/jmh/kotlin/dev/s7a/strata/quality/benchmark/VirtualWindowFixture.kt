package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText

/**
 * Immutable model corpus and owner-confined navigation recipe with independent full-output references.
 * Untimed work checks admit the historical full-window factory path or the candidate overlapping path, never missing required rows.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class VirtualWindowFixture(
    private val window: VirtualWindowBenchmark.Window,
    private val factory: VirtualWindowBenchmark.Factory,
    private val motion: VirtualWindowBenchmark.Motion,
) : AutoCloseable {
    private val models = List(4_096) { Model(it) }
    private val state = VirtualListState<Int>()
    private val definition = mutableStateOf(0)
    private val direct = mutableStateOf(0)
    private val source = BenchmarkStateSource(0)
    private var sourceValue = 0
    private var presentation = 0
    private var step = 0
    private val counts = Counts()
    private val size = IntSize(8, (window.rows - 2) * 10)
    private val constraints = Constraints.fixed(size.width, size.height)
    private val session: RuntimeUiSession =
        createRuntimeUiSession {
            val captured = definition.value
            counts.roots += 1
            evaluateComponentTree {
                VirtualList(
                    itemCount = models.size,
                    itemAt = models::get,
                    keyAt = { models[it].index },
                    indexOfKey = { it.takeIf { index -> index in models.indices } },
                    state = state,
                    viewportSize = size,
                    rowHeight = 10,
                ) { model ->
                    counts.factories += 1
                    val value = captured + presentation
                    when (factory) {
                        VirtualWindowBenchmark.Factory.Simple -> element(LeafElement(model, value, counts))
                        VirtualWindowBenchmark.Factory.Deep32 -> deepRow(model, value, 32)
                        VirtualWindowBenchmark.Factory.DirectState -> element(LeafElement(model, value + direct.value, counts))
                        VirtualWindowBenchmark.Factory.Observed -> Observe(source) { observed -> element(LeafElement(model, value + observed, counts)) }
                    }
                }
            }
        }

    /**
     * Primes an interior integral window before work checks and collection.
     */
    fun attach() {
        session.attach()
        session.frame(constraints)
        state.scrollState.scrollTo(1_000.0)
        session.frame(constraints)
    }

    /**
     * Alternates two fixed offsets or publishes a complete invalidation before committing a frame.
     */
    fun nextFrame(): RuntimeUiFrame {
        step += 1
        val alternate = step % 2 == 1
        when (motion) {
            VirtualWindowBenchmark.Motion.Clean -> {
                Unit
            }

            VirtualWindowBenchmark.Motion.OneRow -> {
                state.scrollState.scrollTo(if (alternate) 1_010.0 else 1_000.0)
            }

            VirtualWindowBenchmark.Motion.Fractional -> {
                state.scrollState.scrollTo(if (alternate) 1_001.25 else 1_000.0)
            }

            VirtualWindowBenchmark.Motion.FastJump -> {
                state.scrollState.scrollTo(if (alternate) 30_000.0 else 1_000.0)
            }

            VirtualWindowBenchmark.Motion.Refresh -> {
                presentation = if (alternate) 1 else 0
                state.refresh()
            }

            VirtualWindowBenchmark.Motion.DefinitionReplacement -> {
                definition.value = if (alternate) 1 else 0
            }
        }
        return session.frame(constraints)
    }

    /**
     * Requires exact factory accounting, necessary invalidations and detached full-output parity outside collection.
     */
    fun verifyWork() {
        val original = session.frame(constraints)
        verifyFrame(original)
        check(session.frame(constraints) === original)
        val oldCommands = original.drawCommands.toList()
        val oldSemantics = original.semantics.toList()
        val monitor = session.startRenderMonitoring()
        try {
            repeat(4) {
                val previous = indices()
                val before = counts.factories
                val beforeCreated = counts.created
                val beforeRoots = counts.roots
                val rowWork = monitor.snapshot().counts.getValue(UiRenderMetric.RowEvaluation)
                val nodeWork = monitor.snapshot().counts.getValue(UiRenderMetric.NodeCreate)
                val frame = nextFrame()
                val current = indices()
                val actual = counts.factories - before
                val required = requiredFactories(previous, current)
                check(actual == required || (admitsHistoricalFullWindow() && actual == current.count())) {
                    "$window/$factory/$motion factory work $actual, required $required or historical full window"
                }
                check(monitor.snapshot().counts.getValue(UiRenderMetric.RowEvaluation) - rowWork == actual.toLong())
                check(counts.created - beforeCreated == current.count { (it in previous).not() })
                val freshRows = current.count { (it in previous).not() }
                val nodesPerRow =
                    when (factory) {
                        VirtualWindowBenchmark.Factory.Deep32 -> 34L
                        VirtualWindowBenchmark.Factory.Observed -> 3L
                        else -> 2L
                    }
                check(monitor.snapshot().counts.getValue(UiRenderMetric.NodeCreate) - nodeWork == freshRows * nodesPerRow)
                check(counts.roots - beforeRoots == if (motion == VirtualWindowBenchmark.Motion.DefinitionReplacement) 1 else 0)
                verifyFrame(frame)
            }
        } finally {
            monitor.close()
        }
        check(original.drawCommands == oldCommands && original.semantics == oldSemantics)
        verifyObservation()
        val beforeDetach = session.frame(constraints)
        session.detach()
        check(source.subscribed.not())
        session.attach()
        val reattached = session.frame(constraints)
        check(reattached.drawCommands == beforeDetach.drawCommands && reattached.semantics == beforeDetach.semantics)
        verifyFrame(reattached)
        verifyOwnerIsolation()
        state.scrollState.scrollTo(1_000.0)
        verifyFrame(session.frame(constraints))
    }

    private fun requiredFactories(
        previous: IntRange,
        current: IntRange,
    ): Int =
        when (motion) {
            VirtualWindowBenchmark.Motion.Clean -> 0
            VirtualWindowBenchmark.Motion.Refresh, VirtualWindowBenchmark.Motion.DefinitionReplacement -> current.count()
            else -> if (factory == VirtualWindowBenchmark.Factory.DirectState) current.count() else current.count { (it in previous).not() }
        }

    private fun admitsHistoricalFullWindow(): Boolean = motion == VirtualWindowBenchmark.Motion.OneRow || motion == VirtualWindowBenchmark.Motion.Fractional || motion == VirtualWindowBenchmark.Motion.FastJump

    private fun verifyObservation() {
        if (factory == VirtualWindowBenchmark.Factory.Observed) {
            val before = counts.factories
            sourceValue = 2
            source.publish(sourceValue)
            verifyFrame(session.frame(constraints))
            check(counts.factories == before && source.subscribed)
        }
        if (factory == VirtualWindowBenchmark.Factory.DirectState) {
            direct.value = 2
            state.scrollState.scrollTo(1_010.0)
            verifyFrame(session.frame(constraints))
        }
    }

    private fun verifyOwnerIsolation() {
        VirtualWindowFixture(window, factory, VirtualWindowBenchmark.Motion.Clean).use { other ->
            other.attach()
            val first = session.frame(constraints)
            other.nextFrame()
            check(session.frame(constraints) === first)
            check(other.counts !== counts && other.state !== state)
        }
    }

    private fun indices(): IntRange {
        val offset = state.scrollState.metrics.offset
        val first = (offset / 10.0).toInt()
        val last = ((offset + size.height - 1.0) / 10.0).toInt()
        return maxOf(0, first - 1)..minOf(models.lastIndex, last + 1)
    }

    private fun currentValue(): Int =
        definition.value + presentation +
            when (factory) {
                VirtualWindowBenchmark.Factory.DirectState -> direct.value
                VirtualWindowBenchmark.Factory.Observed -> sourceValue
                else -> 0
            }

    private fun verifyFrame(frame: RuntimeUiFrame) {
        val value = currentValue()
        val offset =
            state.scrollState.metrics.offset
                .toInt()
        val indices = indices()
        val bounds = indices.map { index -> IntRect(0, index * 10 - offset, size.width, index * 10 - offset + 10) }
        check(frame.size == size)
        check(frame.semantics.map { it.bounds } == bounds)
        check(frame.semantics.map { it.semantics } == indices.map { semantic(it, value) })
        val fills = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
        check(fills.map { it.bounds } == bounds)
        check(fills.map { it.color } == indices.map { color(it, value) })
        val pixels = rasterizeHeadless(frame.drawCommands, size).copyArgb()
        for (y in 0 until size.height) {
            val row = (y + offset) / 10
            for (x in 0 until size.width) check(pixels[y * size.width + x] == color(row, value).value)
        }
        check(session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))) == InputResult.Consumed)
        check(counts.pointer == offset / 10)
        check(counts.pointerPosition == IntOffset(1, offset % 10 + 1))
        check(counts.live == indices.count())
    }

    private fun UiScope.deepRow(
        model: Model,
        value: Int,
        depth: Int,
    ) {
        if (depth == 0) {
            element(LeafElement(model, value, counts))
        } else {
            Stack { deepRow(model, value, depth - 1) }
        }
    }

    override fun close() {
        session.close()
        check(counts.live == 0 && counts.created == counts.disposed)
        check(source.subscribed.not())
        session.close()
        check(counts.created == counts.disposed)
    }

    /**
     * Stable immutable model references, distinct from row declaration and retained-node identities.
     */
    private class Model(
        val index: Int,
    )

    /**
     * Actual callback and leaf ownership counters, never estimates of runtime work.
     */
    private class Counts {
        var factories = 0
        var roots = 0
        var created = 0
        var disposed = 0
        var live = 0
        var pointer: Int? = null
        var pointerPosition: IntOffset? = null
    }

    /**
     * Painted fixed-height row leaf, also exposing input and unresolved semantics.
     */
    private class LeafElement(
        val model: Model,
        val value: Int,
        val counts: Counts,
    ) : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable primitive token with value-sensitive presentation invalidation.
         */
        companion object {
            val TYPE: ElementType<LeafElement, LeafNode> =
                ElementType(
                    elementClass = LeafElement::class,
                    nodeClass = LeafNode::class,
                    validateLocal = {},
                    createNode = { LeafNode(it.model.index, it.value, it.counts) },
                    updateNode = { previous, current, node ->
                        node.value = current.value
                        if (previous.value == current.value) DirtyMask.None else DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)
                    },
                )
        }
    }

    /**
     * Owner-confined retained leaf whose work is included in both runtime targets.
     */
    private class LeafNode(
        private val index: Int,
        var value: Int,
        private val counts: Counts,
    ) : Node(),
        MeasureNode,
        PaintNode,
        SemanticsNode,
        PointerInputNode,
        LifecycleNode {
        init {
            counts.created += 1
        }

        override fun attach() {
            counts.live += 1
        }

        override fun detach() = Unit

        override fun dispose() {
            counts.live -= 1
            counts.disposed += 1
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(8, 10))

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color(index, value))
        }

        override fun semantics(scope: SemanticsScope) = scope.emit(semantic(index, value))

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            if (event is PointerEvent.Scroll) return InputResult.Ignored
            counts.pointer = index
            counts.pointerPosition = localPosition
            return InputResult.Consumed
        }
    }

    /**
     * Independent opaque pixels and unresolved accessibility values for the immutable recipe.
     */
    private companion object {
        fun color(
            index: Int,
            value: Int,
        ): ArgbColor = ArgbColor(0xFF000000.toInt() or ((index * 67 + value * 13) and 0xFFFFFF))

        fun semantic(
            index: Int,
            value: Int,
        ): Semantics = Semantics(label = UiText.Literal("row-$index"), value = UiText.Literal(value.toString()))
    }
}
