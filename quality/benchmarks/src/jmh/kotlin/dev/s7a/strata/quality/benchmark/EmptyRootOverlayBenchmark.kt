package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures complete invalidated retained frames with sparse or absent root overlays.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class EmptyRootOverlayBenchmark {
    /**
     * Changes every ordinary leaf's admitted input and returns the completed frame.
     */
    @Benchmark
    public fun changedFrame(scene: Scene): RuntimeUiFrame = scene.nextFrame()

    /**
     * Root-overlay capability and output selected before construction.
     */
    public enum class Pattern {
        Absent,
        CallbackEmpty,
        RootNonempty,

        /**
         * One overlay leaf below sixteen ordinary ancestors.
         */
        DescendantNonempty,
    }

    /**
     * Alternates leaf color or measured width while preserving the declared tree.
     */
    public enum class Invalidation {
        Paint,
        Geometry,
    }

    /**
     * One fixed, fully placed retained tree owned by the JMH worker.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Number of ordinary-painted rows.
         */
        @JvmField
        @Param("1", "16", "128", "1024")
        public var leafCount: Int = 1

        /**
         * Root-overlay shape established outside timing.
         */
        @JvmField
        @Param("Absent", "CallbackEmpty", "RootNonempty", "DescendantNonempty")
        public var pattern: Pattern = Pattern.Absent

        /**
         * Input and invalidation phase changed by each invocation.
         */
        @JvmField
        @Param("Paint", "Geometry")
        public var invalidation: Invalidation = Invalidation.Paint

        /**
         * Primary timed cases keep diagnostic collection disabled.
         */
        @JvmField
        @Param("false")
        public var monitoring: Boolean = false

        private lateinit var session: RuntimeUiSession
        private lateinit var root: ContainerNode
        private lateinit var leaves: List<LeafNode>
        private val nodes = ArrayList<FixtureNode>()
        private var alternate = false

        /**
         * Builds and settles the fixed viewport before measured invocations.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            require(monitoring.not())
            require(leafCount in listOf(1, 16, 128, 1024))
            leaves =
                List(leafCount) { index ->
                    if (pattern == Pattern.DescendantNonempty && index == 0) OverlayLeafNode() else LeafNode()
                }
            val children =
                leaves.mapIndexed { index, leaf ->
                    var branch: Element = element(leaf)
                    if (pattern == Pattern.DescendantNonempty && index == 0) {
                        repeat(16) { branch = element(ContainerNode(ContainerKind.Ancestor), listOf(branch)) }
                    }
                    branch
                }
            root =
                when (pattern) {
                    Pattern.CallbackEmpty -> RootOverlayNode(emitsOverlay = false)
                    Pattern.RootNonempty -> RootOverlayNode(emitsOverlay = true)
                    Pattern.Absent, Pattern.DescendantNonempty -> ContainerNode(ContainerKind.Root)
                }
            val description = element(root, children)
            session = createRuntimeUiSession { description }
            session.attach()
            val initial = session.frame(CONSTRAINTS)
            check(initial.drawCommands == expectedCommands())
            check(session.frame(CONSTRAINTS) === initial)
        }

        /**
         * Publishes one alternating leaf input without constructing declarations.
         * Paint also invalidates the root so its applicable callback executes in every case.
         */
        public fun nextFrame(): RuntimeUiFrame {
            alternate = alternate.not()
            for (leaf in leaves) leaf.publish(invalidation, alternate)
            if (invalidation == Invalidation.Paint) root.invalidatePaint()
            return session.frame(CONSTRAINTS)
        }

        /**
         * Checks exact output, callback work, clean reuse, and old-frame stability outside timing.
         */
        public fun verifyWork() {
            val initial = session.frame(CONSTRAINTS)
            val oldCommands = initial.drawCommands.toList()
            session.startRenderMonitoring().use { monitor ->
                repeat(4) {
                    monitor.checkpoint()
                    val frame = nextFrame()
                    check(frame.size == VIEWPORT)
                    check(frame.drawCommands == expectedCommands())
                    check(frame.semantics.isEmpty())
                    val snapshot = monitor.snapshot()
                    check(snapshot.overflowed.not())
                    val containers = if (pattern == Pattern.DescendantNonempty) 17L else 1L
                    val geometry = invalidation == Invalidation.Geometry
                    val overlay = if (pattern == Pattern.Absent) 0L else 1L
                    check(snapshot.counts[UiRenderMetric.Paint] == leafCount.toLong())
                    check(snapshot.counts[UiRenderMetric.OverlayPaint] == 0L)
                    check(snapshot.counts[UiRenderMetric.RootOverlayPaint] == overlay)
                    check(snapshot.counts[UiRenderMetric.Measure] == if (geometry) leafCount + containers else 0L)
                    check(snapshot.counts[UiRenderMetric.Layout] == if (geometry) containers else 0L)
                    check(snapshot.counts[UiRenderMetric.FrameSuccess] == 1L)
                    monitor.checkpoint()
                    check(session.frame(CONSTRAINTS) === frame)
                    check(monitor.snapshot().counts[UiRenderMetric.Paint] == 0L)
                    check(monitor.snapshot().counts[UiRenderMetric.RootOverlayPaint] == 0L)
                    check(initial.drawCommands == oldCommands)
                }
            }
            session.close()
            check(initial.drawCommands == oldCommands)
            check(nodes.all { it.detachCalls == 1 && it.disposeCalls == 1 })
        }

        /**
         * Releases every retained node on its worker; cleanup is idempotent after verification.
         */
        @TearDown(Level.Trial)
        public fun close() {
            session.close()
            check(nodes.all { it.detachCalls == 1 && it.disposeCalls == 1 })
        }

        private fun element(
            node: FixtureNode,
            children: List<Element> = emptyList(),
        ): Element {
            nodes.add(node)
            return FixtureElement(node, children)
        }

        private fun expectedCommands(): List<DrawCommand> {
            val width = if (invalidation == Invalidation.Geometry && alternate) 2 else 1
            val color = if (invalidation == Invalidation.Paint && alternate) SECOND else FIRST
            return buildList {
                repeat(leafCount) { row -> add(DrawCommand.FillRectangle(IntRect(0, row, width, row + 1), color)) }
                when (pattern) {
                    Pattern.RootNonempty -> add(DrawCommand.FillRectangle(ROOT_OVERLAY, OVERLAY))
                    Pattern.DescendantNonempty -> add(DrawCommand.FillRectangle(DESCENDANT_OVERLAY, OVERLAY))
                    Pattern.Absent, Pattern.CallbackEmpty -> Unit
                }
            }
        }
    }

    /**
     * Runs the full parameter product through the shared untimed verifier discovery.
     */
    public companion object {
        private val VIEWPORT = IntSize(4, 1_040)
        private val CONSTRAINTS = Constraints.fixed(VIEWPORT.width, VIEWPORT.height)
        private val CHILD_CONSTRAINTS = Constraints(maxWidth = VIEWPORT.width, maxHeight = VIEWPORT.height)
        private val FIRST = ArgbColor(0xFF123456.toInt())
        private val SECOND = ArgbColor(0xFF654321.toInt())
        private val OVERLAY = ArgbColor(0xFFABCDEF.toInt())
        private val ROOT_OVERLAY = IntRect(2, 1_039, 3, 1_040)
        private val DESCENDANT_OVERLAY = IntRect(2, 0, 3, 1)

        /**
         * Requires all thirty-two scenes and both admitted JMH modes before formal collection.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(EmptyRootOverlayBenchmark::class.java), setOf("avgt", "sample")).size == 64)
            for (count in listOf(1, 16, 128, 1024)) {
                for (pattern in Pattern.entries) {
                    for (invalidation in Invalidation.entries) {
                        val scene = Scene()
                        scene.leafCount = count
                        scene.pattern = pattern
                        scene.invalidation = invalidation
                        try {
                            scene.setUp()
                            scene.verifyWork()
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        }
    }

    /**
     * Separates the fixed root viewport from single-child ancestor geometry.
     */
    private enum class ContainerKind {
        Root,
        Ancestor,
    }

    /**
     * Records terminal lifecycle attempts without instrumenting timed paint callbacks.
     */
    private abstract class FixtureNode :
        Node(),
        LifecycleNode {
        var detachCalls = 0
        var disposeCalls = 0

        override fun attach() = Unit

        override fun detach() {
            detachCalls += 1
        }

        override fun dispose() {
            disposeCalls += 1
        }
    }

    /**
     * Measures every declared child and places all rows without clipping.
     */
    private open class ContainerNode(
        private val kind: ContainerKind,
    ) : FixtureNode(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            var first = IntSize.Zero
            for (index in 0 until scope.childCount) {
                val measured = scope.measureChild(index, CHILD_CONSTRAINTS)
                if (index == 0) first = measured
            }
            return constraints.constrain(if (kind == ContainerKind.Root) VIEWPORT else first)
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) {
                scope.placeChild(index, if (kind == ContainerKind.Root) IntOffset(0, index) else IntOffset.Zero)
            }
        }

        /**
         * Requests root callback refresh alongside leaf paint input changes.
         */
        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    /**
     * Keeps an empty root callback applicable or contributes one deterministic root command.
     */
    private class RootOverlayNode(
        private val emitsOverlay: Boolean,
    ) : ContainerNode(ContainerKind.Root),
        RootOverlayPaintNode {
        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            if (emitsOverlay) scope.fillRectangle(ROOT_OVERLAY, OVERLAY)
        }
    }

    /**
     * A row with independently invalidated color or measured width.
     */
    private open class LeafNode :
        FixtureNode(),
        MeasureNode,
        PaintNode {
        private var width = 1
        private var color = FIRST

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(width, 1))

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, 1), color)
        }

        /**
         * Publishes an alternating input under the owning retained session.
         */
        fun publish(
            invalidation: Invalidation,
            alternate: Boolean,
        ) {
            when (invalidation) {
                Invalidation.Paint -> {
                    color = if (alternate) SECOND else FIRST
                    invalidate(DirtyMask.of(DirtyPhase.Paint))
                }

                Invalidation.Geometry -> {
                    width = if (alternate) 2 else 1
                    invalidate(DirtyMask.of(DirtyPhase.Measure))
                }
            }
        }
    }

    /**
     * The only overlay-producing descendant; its sixteen ancestors remain overlay-free.
     */
    private class OverlayLeafNode :
        LeafNode(),
        RootOverlayPaintNode {
        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            scope.fillRectangle(DESCENDANT_OVERLAY, OVERLAY)
        }
    }

    /**
     * A stable typed declaration for a fresh, fixture-owned retained node.
     */
    private class FixtureElement(
        val node: FixtureNode,
        children: List<Element>,
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<FixtureElement, FixtureNode> =
                ElementType(
                    elementClass = FixtureElement::class,
                    nodeClass = FixtureNode::class,
                    validateLocal = {},
                    createNode = { it.node },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
