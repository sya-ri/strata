package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.quality.benchmark.ContainerConstructionBenchmark.Container
import dev.s7a.strata.quality.benchmark.ContainerConstructionBenchmark.Shape
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText

/**
 * Frozen declaration recipe with independent layout arithmetic and owner-local lifecycle counters.
 * No timing or collector implementation belongs in this fixture.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // One frozen recipe owns construction, independent oracles, and terminal checks.
internal class ContainerDeclarationFixture(
    private val container: Container,
    private val shape: Shape,
) {
    private val counts = Counts()
    private val version = mutableStateOf(0)
    private val constraints = Constraints(maxWidth = 4_096, maxHeight = 4_096)
    private val parentCount = shape.groups * (shape.depth + 1)
    private val leafCount = shape.groups * (shape.depth + shape.width)
    private val states = List(parentCount) { PanZoomState() }
    private val source =
        object : TiledImageSource {
            override val bounds: LongRect = LongRect(0L, 0L, 1_024L, 1_024L)
            override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(1_024, 1_024), 1L))
            private val tiles =
                StateSource<TiledImageTile> {
                    counts.tileSubscriptions += 1
                    StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Empty)) { counts.tileSubscriptions -= 1 }
                }

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                check(id == TiledImageTileId(0, 0L, 0L))
                return tiles
            }
        }
    private val templateModifier = templateModifier()
    private val templates = List(2) { value -> List(shape.width) { LeafElement(it, value, counts, templateModifier) } }
    private val session: RuntimeUiSession =
        createRuntimeUiSession {
            counts.rootEvaluations += 1
            construct(version.value)
        }
    private var closed = false

    /**
     * Builds fresh parent descriptions; repeated-template scenes reuse only immutable leaf inputs.
     */
    fun construct(value: Int): Element {
        var nextLeaf = 0
        var nextParent = 0

        fun UiScope.branch(
            depth: Int,
            parentModifier: Modifier,
        ) {
            val state = states[nextParent++]
            val content: UiScope.(Modifier) -> Unit = { childModifier ->
                if (0 < depth) {
                    element(LeafElement(nextLeaf++, value, counts, childModifier))
                    branch(depth - 1, childModifier)
                } else {
                    repeat(shape.width) { index ->
                        val leaf =
                            if (shape.template) {
                                templates[value][index]
                            } else {
                                LeafElement(nextLeaf++, value, counts, childModifier)
                            }
                        element(leaf)
                    }
                }
            }
            when (container) {
                Container.Row -> {
                    Row(modifier = parentModifier) { content(Modifier.Empty) }
                }

                Container.FlowRow -> {
                    FlowRow(modifier = parentModifier) { content(Modifier.Empty) }
                }

                Container.Column -> {
                    Column(modifier = parentModifier) { content(Modifier.Empty) }
                }

                Container.Stack -> {
                    Stack(modifier = parentModifier) { content(Modifier.Empty) }
                }

                Container.Grid -> {
                    Grid(columns = 4, modifier = parentModifier) { content(Modifier.Empty) }
                }

                Container.TiledImage -> {
                    TiledImage(source, state, IntSize(1_024, 1_024), modifier = parentModifier) {
                        content(Modifier.Empty.atContentPosition(DoubleOffset.Zero, Alignment.TopStart))
                    }
                }
            }
        }
        return evaluateComponentTree { Stack { repeat(shape.groups) { branch(shape.depth, Modifier.Empty) } } }
    }

    /**
     * Creates and primes the session outside collection.
     */
    fun attach() {
        session.attach()
        session.frame(constraints)
    }

    /**
     * Publishes a real root dependency revision and commits the rebuilt retained declaration.
     */
    fun rebuildFrame(): RuntimeUiFrame {
        version.value = 1 - version.value
        return session.frame(constraints)
    }

    /**
     * Checks the complete declaration and retained pixel, geometry, input and lifecycle matrix.
     * Assertions and diagnostic counters run outside timing.
     */
    fun verifyWork() {
        val originalDeclaration = construct(0)
        val originalLeaves = leaves(originalDeclaration)
        val expectedOrdinals =
            if (shape.template) List(shape.groups) { (0 until shape.width).toList() }.flatten() else (0 until leafCount).toList()
        check(originalLeaves.map { it.ordinal } == expectedOrdinals)
        val tileLayers = if (container == Container.TiledImage) parentCount else 0
        check(elementCount(originalDeclaration) == 1 + parentCount + tileLayers + leafCount)
        val expectedCopySlots =
            shape.groups * (shape.depth * 2 + if (2 <= shape.width) shape.width else 0) +
                if (2 <= shape.groups) shape.groups else 0
        check(redundantCopySlots(originalDeclaration, root = true) == expectedCopySlots)
        val expected = expectedLayout()
        check(expected.leaves.map { it.ordinal } == expectedOrdinals)
        check(counts.created == leafCount && counts.live == leafCount)
        val originalFrame = session.frame(constraints)
        verifyFrame(originalFrame, expected, version.value)
        val oldSemantics = originalFrame.semantics.toList()
        val oldPixels = pixels(originalFrame)
        repeat(3) {
            val updates = counts.updates
            val evaluations = counts.rootEvaluations
            val measures = counts.measures
            val paints = counts.paints
            val semantics = counts.semantics
            val frame = rebuildFrame()
            check(counts.rootEvaluations == evaluations + 1)
            check(counts.created == leafCount && counts.live == leafCount && counts.disposed == 0)
            check(counts.updates == updates + leafCount)
            check(counts.measures == measures && counts.paints == paints + leafCount && counts.semantics == semantics + leafCount)
            verifyFrame(frame, expected, version.value)
            check(session.frame(constraints) === frame)
            check(originalLeaves.map { it.ordinal } == expectedOrdinals)
            check(originalDeclaration.children.size == shape.groups)
            check(originalFrame.semantics == oldSemantics)
            check(pixels(originalFrame).contentEquals(oldPixels))
        }
        val retained = counts.live
        session.detach()
        session.attach()
        verifyFrame(session.frame(constraints), expected, version.value)
        check(counts.created == leafCount && counts.live == retained && counts.disposed == 0)
        val hit = expected.leaves.lastOrNull { it.bounds.left == 0 && it.bounds.top == 0 }
        counts.lastPointer = null
        val result = session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary))
        check(result == if (hit == null) InputResult.Ignored else InputResult.Consumed)
        check(counts.lastPointer == hit?.ordinal)
    }

    /**
     * Releases all retained children and tile observations while this fixture handle remains reachable.
     */
    fun close() {
        if (closed) return
        closed = true
        session.close()
        check(counts.live == 0 && counts.disposed == counts.created && counts.tileSubscriptions == 0)
        session.close()
    }

    private fun verifyFrame(
        frame: RuntimeUiFrame,
        layout: Layout,
        value: Int,
    ) {
        check(frame.size == layout.size)
        check(frame.semantics.size == leafCount)
        frame.semantics.zip(layout.leaves).forEach { (entry, leaf) ->
            check(entry.bounds == leaf.bounds)
            check(entry.semantics == semantic(leaf.ordinal, value))
        }
        val rasterSize = rasterSize(layout.size)
        val pixels = IntArray(rasterSize.width * rasterSize.height)
        for (leaf in layout.leaves) {
            for (y in leaf.bounds.top until leaf.bounds.bottom) {
                for (x in leaf.bounds.left until leaf.bounds.right) {
                    pixels[y * rasterSize.width + x] = color(leaf.ordinal, value).value
                }
            }
        }
        check(pixels(frame).contentEquals(pixels))
    }

    private fun pixels(frame: RuntimeUiFrame): IntArray = rasterizeHeadless(frame.drawCommands, rasterSize(frame.size)).copyArgb()

    private fun rasterSize(size: IntSize): IntSize = IntSize(maxOf(1, size.width), maxOf(1, size.height))

    private fun templateModifier(): Modifier {
        if (container != Container.TiledImage) return Modifier.Empty
        var result = Modifier.Empty
        evaluateComponentTree {
            TiledImage(source, states[0], IntSize(1_024, 1_024)) {
                result = Modifier.Empty.atContentPosition(DoubleOffset.Zero, Alignment.TopStart)
            }
        }
        return result
    }

    private fun expectedLayout(): Layout {
        var nextLeaf = 0

        fun branch(depth: Int): Layout {
            val children =
                if (0 < depth) {
                    listOf(Layout.leaf(nextLeaf++), branch(depth - 1))
                } else {
                    List(shape.width) { index -> Layout.leaf(if (shape.template) index else nextLeaf++) }
                }
            return layout(container, children)
        }
        return layout(Container.Stack, List(shape.groups) { branch(shape.depth) })
    }

    private fun leaves(element: Element): List<LeafElement> = if (element is LeafElement) listOf(element) else element.children.flatMap(::leaves)

    private fun elementCount(element: Element): Int = 1 + element.children.sumOf(::elementCount)

    private fun redundantCopySlots(
        element: Element,
        root: Boolean,
    ): Int {
        if (element is LeafElement) return 0
        val count = element.children.size - if (root.not() && container == Container.TiledImage && element.children.isNotEmpty()) 1 else 0
        val current = if (2 <= count) count else 0
        return current + element.children.sumOf { redundantCopySlots(it, root = false) }
    }

    private class Counts {
        var created = 0
        var live = 0
        var disposed = 0
        var updates = 0
        var tileSubscriptions = 0
        var lastPointer: Int? = null
        var rootEvaluations = 0
        var measures = 0
        var paints = 0
        var semantics = 0
    }

    private data class PlacedLeaf(
        val ordinal: Int,
        val bounds: IntRect,
    )

    private data class Layout(
        val size: IntSize,
        val leaves: List<PlacedLeaf>,
    ) {
        fun at(
            x: Int,
            y: Int,
        ): List<PlacedLeaf> = leaves.map { it.copy(bounds = IntRect(it.bounds.left + x, it.bounds.top + y, it.bounds.right + x, it.bounds.bottom + y)) }

        companion object {
            fun leaf(ordinal: Int): Layout = Layout(IntSize(1, 1), listOf(PlacedLeaf(ordinal, IntRect(0, 0, 1, 1))))
        }
    }

    private class LeafElement(
        val ordinal: Int,
        val value: Int,
        val counts: Counts,
        modifier: Modifier = Modifier.Empty,
    ) : Element(ElementIdentity.Positional, TYPE, modifier = modifier) {
        companion object {
            val TYPE: ElementType<LeafElement, LeafNode> =
                ElementType(
                    elementClass = LeafElement::class,
                    nodeClass = LeafNode::class,
                    validateLocal = {},
                    createNode = { LeafNode(it.ordinal, it.value, it.counts) },
                    updateNode = { _, current, node ->
                        node.counts.updates += 1
                        node.value = current.value
                        DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)
                    },
                )
        }
    }

    private class LeafNode(
        val ordinal: Int,
        var value: Int,
        val counts: Counts,
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
        ): IntSize {
            counts.measures += 1
            return constraints.constrain(IntSize(1, 1))
        }

        override fun paint(scope: PaintScope) {
            counts.paints += 1
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color(ordinal, value))
        }

        override fun semantics(scope: SemanticsScope) {
            counts.semantics += 1
            scope.emit(semantic(ordinal, value))
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            counts.lastPointer = ordinal
            return InputResult.Consumed
        }
    }

    internal companion object {
        private fun color(
            ordinal: Int,
            value: Int,
        ): ArgbColor = ArgbColor(0xFF000000.toInt() or (ordinal * 73_471 and 0xFFFFFF) xor (value * 0x00010101))

        private fun semantic(
            ordinal: Int,
            value: Int,
        ): Semantics = Semantics(label = UiText.Literal("leaf-$ordinal"), value = UiText.Literal("version-$value"))

        private fun layout(
            container: Container,
            children: List<Layout>,
        ): Layout {
            if (container == Container.TiledImage) return Layout(IntSize(1_024, 1_024), children.flatMap { it.leaves })
            if (children.isEmpty()) return Layout(IntSize.Zero, emptyList())
            val columns = IntArray(4)
            val rows = IntArray((children.size + 3) / 4)
            children.forEachIndexed { index, child ->
                columns[index % 4] = maxOf(columns[index % 4], child.size.width)
                rows[index / 4] = maxOf(rows[index / 4], child.size.height)
            }
            val placements = ArrayList<PlacedLeaf>()
            var x = 0
            var y = 0
            children.forEachIndexed { index, child ->
                when (container) {
                    Container.Row, Container.FlowRow -> {
                        placements += child.at(x, 0)
                        x += child.size.width
                    }

                    Container.Column -> {
                        placements += child.at(0, y)
                        y += child.size.height
                    }

                    Container.Stack -> {
                        placements += child.leaves
                    }

                    Container.Grid -> {
                        placements += child.at(columns.take(index % 4).sum(), rows.take(index / 4).sum())
                    }

                    Container.TiledImage -> {
                        error("Tiled layout is handled above.")
                    }
                }
            }
            val size = naturalSize(container, children, columns, rows)
            return Layout(size, placements)
        }

        private fun naturalSize(
            container: Container,
            children: List<Layout>,
            columns: IntArray,
            rows: IntArray,
        ): IntSize =
            when (container) {
                Container.Row, Container.FlowRow -> IntSize(children.sumOf { it.size.width }, children.maxOf { it.size.height })
                Container.Column -> IntSize(children.maxOf { it.size.width }, children.sumOf { it.size.height })
                Container.Stack -> IntSize(children.maxOf { it.size.width }, children.maxOf { it.size.height })
                Container.Grid -> IntSize(columns.sum(), rows.sum())
                Container.TiledImage -> IntSize(1_024, 1_024)
            }

        /**
         * Independently verifies weighted and aligned parent data plus session-owner isolation.
         */
        fun verifyParentData() {
            val counts = Counts()
            val declaration =
                evaluateComponentTree {
                    Row(modifier = Modifier.Empty.size(12, 4), spacing = 2) {
                        element(LeafElement(0, 0, counts, Modifier.Empty.weight(1f).align(VerticalAlignment.Bottom)))
                        element(LeafElement(1, 0, counts, Modifier.Empty.weight(1f).align(VerticalAlignment.Top)))
                    }
                }
            val session = createRuntimeUiSession { declaration }
            try {
                session.attach()
                val frame = session.frame(Constraints(maxWidth = 12, maxHeight = 4))
                check(frame.size == IntSize(12, 4))
                check(frame.semantics.map { it.bounds } == listOf(IntRect(0, 3, 5, 4), IntRect(7, 0, 12, 1)))
            } finally {
                session.close()
            }
            check(counts.live == 0 && counts.created == 2 && counts.disposed == 2)
            val first = ContainerDeclarationFixture(Container.Row, Shape.Eight)
            val second = ContainerDeclarationFixture(Container.Row, Shape.Eight)
            try {
                first.attach()
                second.attach()
                val unchanged = second.session.frame(second.constraints)
                first.rebuildFrame()
                check(second.session.frame(second.constraints) === unchanged)
                first.close()
                check(second.counts.live == 8)
            } finally {
                first.close()
                second.close()
            }
        }
    }
}
