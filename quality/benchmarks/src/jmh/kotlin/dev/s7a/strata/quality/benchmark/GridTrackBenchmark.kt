package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures complete public-Grid frames with fixed nonuniform row-major children.
 * Trial setup/cleanup and independent geometry/work checks remain outside timing.
 * Mutations, alignment declaration rebuilding and the complete selected frame remain inside timing.
 * Rasterization, upload, GPU work and presentation are absent from these CPU-side boundaries.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class GridTrackBenchmark {
    /**
     * Borrows a primed unchanged frame; this is the no-layout control.
     */
    @Benchmark
    public fun cleanFrame(scene: Scene): RuntimeUiFrame = scene.cleanFrame()

    /**
     * Changes one retained leaf's size and completes the resulting frame; Empty is an unchanged control.
     */
    @Benchmark
    public fun childSizeFrame(scene: Scene): RuntimeUiFrame = scene.childSizeFrame()

    /**
     * Changes the actual root constraints and completes all required measurement and layout.
     */
    @Benchmark
    public fun constraintFrame(scene: Scene): RuntimeUiFrame = scene.constraintFrame()

    /**
     * Publishes a default alignment change, rebuilds its declaration and completes the resulting frame.
     */
    @Benchmark
    public fun alignmentFrame(scene: Scene): RuntimeUiFrame = scene.alignmentFrame()

    /**
     * Fixed empty/single, one-column, partial-row and large-square controls.
     */
    public enum class Workload(
        public val children: Int,
        public val columns: Int,
    ) {
        Empty(0, 1),
        Single(1, 1),
        OneColumn128(128, 1),
        Partial128(128, 7),
        OneColumn4096(4_096, 1),
        Partial4096(4_096, 63),
        Square4096(4_096, 64),
    }

    /**
     * One primed owner-thread session, current frame state and fixed leaf membership per JMH worker.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Compiled shape injected before setup, shared by all four measured boundaries.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.Empty

        private lateinit var session: RuntimeUiSession
        private lateinit var leaves: List<LeafNode>
        private lateinit var descriptions: List<LeafElement>
        private lateinit var grid: Node
        private val alignment = mutableStateOf(Alignment.TopStart)
        private var narrower = false
        private val regularConstraints = Constraints(maxWidth = 8_192, maxHeight = 32_768)
        private val narrowConstraints = Constraints(maxWidth = 8_191, maxHeight = 32_768)
        private val constraints: Constraints
            get() = if (narrower) narrowConstraints else regularConstraints

        /**
         * Creates and primes the fixed public Grid outside timed operations.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            leaves = List(workload.children) { LeafNode(it) }
            descriptions = leaves.map(::LeafElement)
            session =
                createRuntimeUiSession {
                    evaluateComponentTree {
                        Grid(workload.columns, horizontalSpacing = 1, verticalSpacing = 2, contentAlignment = alignment.value) {
                            descriptions.forEach { element(it) }
                        }
                    }
                }
            session.attach()
            session.frame(constraints)
            grid = gridNode(session)
        }

        /**
         * Completes an unchanged frame without mutation or declaration rebuilding.
         */
        public fun cleanFrame(): RuntimeUiFrame = session.frame(constraints)

        /**
         * Changes exactly the first retained leaf; no leaf exists for the Empty control.
         */
        public fun childSizeFrame(): RuntimeUiFrame {
            leaves.firstOrNull()?.changeSize()
            return session.frame(constraints)
        }

        /**
         * Alternates distinct root measurement inputs without changing leaf declarations.
         */
        public fun constraintFrame(): RuntimeUiFrame {
            narrower = narrower.not()
            return session.frame(constraints)
        }

        /**
         * Changes observed default alignment; its public declaration rebuild occurs inside frame.
         */
        public fun alignmentFrame(): RuntimeUiFrame {
            alignment.value = if (alignment.value == Alignment.TopStart) Alignment.BottomEnd else Alignment.TopStart
            return session.frame(constraints)
        }

        /**
         * Checks complete command/semantics equality against an independent track-by-track oracle.
         * Counts real retained leaf callbacks and invokes the loaded Grid layout with a counting scope.
         * Baseline's two-read aggregation and candidate's one-read placement are accepted independently.
         */
        public fun verifyWork() {
            var frame = cleanFrame()
            check(cleanFrame() === frame)
            verifyFrame(frame)
            repeat(3) { _ ->
                val measured = leaves.sumOf { it.measures }
                frame = childSizeFrame()
                check(leaves.sumOf { it.measures } - measured == if (leaves.isEmpty()) 0 else 1)
                verifyFrame(frame)
                check(cleanFrame() === frame)
                val beforeConstraints = leaves.sumOf { it.measures }
                frame = constraintFrame()
                check(leaves.sumOf { it.measures } - beforeConstraints == leaves.size)
                verifyFrame(frame)
                val beforeAlignment = leaves.sumOf { it.measures }
                val tracks = trackArrays(grid)
                frame = alignmentFrame()
                check(leaves.sumOf { it.measures } == beforeAlignment)
                check(trackArrays(grid).zip(tracks).all { (a, b) -> a === b })
                verifyFrame(frame)
                check(cleanFrame() === frame)
            }
            val oracle = expectedBounds()
            val counted = CountingLayout(leaves.map { it.size }, oracle.map { IntOffset(it.left, it.top) })
            (grid as LayoutNode).layout(counted)
            val arrays = trackArrays(grid)
            val readsPerChild = if (arrays.isEmpty()) 2 else 1
            check(counted.reads == leaves.size * readsPerChild && counted.placements == leaves.size)
            if (arrays.isEmpty().not()) {
                check(arrays.map { it?.size } == listOf(minOf(workload.columns, leaves.size), (leaves.size + workload.columns - 1) / workload.columns))
            }
            println("Grid ${workload.name}: layoutSizeReads=${counted.reads}, placements=${counted.placements}, retainedIntSlots=${arrays.sumOf { it?.size ?: 0 }}")
        }

        private fun verifyFrame(frame: RuntimeUiFrame) {
            val bounds = expectedBounds()
            check(frame.drawCommands == bounds.mapIndexed { index, rect -> DrawCommand.FillRectangle(rect, leaves[index].color) })
            check(frame.semantics == bounds.mapIndexed { index, rect -> SemanticsEntry(rect, leaves[index].semantics) })
        }

        // Each track is scanned independently; this does not duplicate Grid's aggregation traversal.
        private fun expectedBounds(): List<IntRect> {
            val columns = workload.columns
            val widths =
                List(minOf(columns, leaves.size)) { column ->
                    leaves.filterIndexed { index, _ -> index % columns == column }.maxOf { it.size.width }
                }
            val heights = leaves.chunked(columns).map { row -> row.maxOf { it.size.height } }
            val columnStarts = widths.runningFold(0) { offset, width -> offset + width + 1 }
            val rowStarts = heights.runningFold(0) { offset, height -> offset + height + 2 }
            return leaves.mapIndexed { index, leaf ->
                val column = index % columns
                val row = index / columns
                val end = alignment.value == Alignment.BottomEnd
                val x = columnStarts[column] + if (end) widths[column] - leaf.size.width else 0
                val y = rowStarts[row] + if (end) heights[row] - leaf.size.height else 0
                IntRect(x, y, x + leaf.size.width, y + leaf.size.height)
            }
        }

        /**
         * Releases the session, then inspects the still-reachable node's actual terminal storage.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (::session.isInitialized) session.close()
            if (::grid.isInitialized) check(trackArrays(grid).all { it == null })
        }
    }

    /**
     * The same static untimed controls run through existing generated-fixture selection and ordinary checks.
     */
    public companion object {
        /**
         * Checks all 28 generated AverageTime cases with identical baseline/candidate fixture code.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(GridTrackBenchmark::class.java), setOf("avgt")).size == 28)
            for (workload in Workload.entries) {
                val scene = Scene()
                scene.workload = workload
                try {
                    scene.setUp()
                    scene.verifyWork()
                } finally {
                    scene.close()
                }
            }
        }

        private fun gridNode(session: RuntimeUiSession): Node {
            val owner =
                session.javaClass
                    .getDeclaredField("session")
                    .apply { isAccessible = true }
                    .get(session)
            val tree =
                owner.javaClass
                    .getDeclaredField("tree")
                    .apply { isAccessible = true }
                    .get(owner)
            val root =
                tree.javaClass
                    .getDeclaredField("root")
                    .apply { isAccessible = true }
                    .get(tree)
            return root.javaClass.getMethod("getNode").invoke(root) as Node
        }

        private fun trackArrays(node: Node): List<IntArray?> =
            node.javaClass.declaredFields.filter { it.type == IntArray::class.java }.sortedBy { it.name }.map { field ->
                field.isAccessible = true
                field.get(node) as IntArray?
            }
    }

    /**
     * Counts actual loaded-node size reads and checks every placement against independent geometry.
     */
    private class CountingLayout(
        private val sizes: List<IntSize>,
        private val offsets: List<IntOffset>,
    ) : LayoutScope {
        var reads = 0
        var placements = 0
        override val size: IntSize = IntSize.Zero
        override val childCount: Int get() = sizes.size

        override fun measuredChildSize(index: Int): IntSize {
            reads += 1
            return sizes[index]
        }

        override fun placeChild(
            index: Int,
            offset: IntOffset,
        ) {
            check(index == placements && offsets[index] == offset)
            placements += 1
        }

        override fun <D : Any> childParentData(
            index: Int,
            key: ParentDataKey<D>,
        ): D? = null
    }

    /**
     * Static child description reused across alignment-only declaration rebuilding.
     */
    private class LeafElement(
        val node: LeafNode,
    ) : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<LeafElement, LeafNode> =
                ElementType(
                    elementClass = LeafElement::class,
                    nodeClass = LeafNode::class,
                    validateLocal = {},
                    createNode = { it.node },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * A size-changing retained first leaf and cached nonuniform siblings, with detached paint/semantics.
     */
    private class LeafNode(
        private val index: Int,
    ) : Node(),
        MeasureNode,
        PaintNode,
        SemanticsNode {
        var measures = 0
        private var enlarged = false
        var size: IntSize = if (index == 0) IntSize(9, 9) else IntSize(index % 5 + 2, index % 3 + 2)
            private set
        val color = ArgbColor(0xFF000000.toInt() or (index * 7_919 and 0xFFFFFF))
        val semantics = Semantics(label = UiText.Literal("grid-$index"))

        /**
         * Changes the first child's track maximum and invalidates measure through actual core ancestry.
         */
        fun changeSize() {
            check(index == 0)
            enlarged = enlarged.not()
            size = if (enlarged) IntSize(10, 10) else IntSize(9, 9)
            invalidate(DirtyMask.of(DirtyPhase.Measure))
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            measures += 1
            return constraints.constrain(size)
        }

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(semantics)
        }
    }
}
