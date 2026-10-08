package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
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

/**
 * Isolates rebuilt-frame semantics handoff with one retained node and a cached local payload.
 * Paint invalidation rebuilds the frame while semantic callbacks and geometry remain cached; clean frames are a separate control.
 * This independent corpus measures frame construction, rather than semantics-heavy tree reconciliation or native rendering.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class SemanticsFrameBenchmark {
    /**
     * Rebuilds one frame containing the fixed current semantics payload.
     */
    @Benchmark
    public fun rebuiltFrame(state: Scene): RuntimeUiFrame = state.changedFrame()

    /**
     * Borrows the unchanged complete frame without rebuilding its semantics.
     */
    @Benchmark
    public fun cleanFrame(state: Scene): RuntimeUiFrame = state.cleanFrame()

    /**
     * One primed owner-thread session per worker with immutable local values.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Current semantic entry count, including standard-library empty/single-entry controls.
         */
        @JvmField
        @Param("0", "1", "128", "1000", "10000")
        public var semanticsCount: Int = 0

        private lateinit var session: RuntimeUiSession
        private lateinit var node: PayloadNode
        private val constraints = Constraints.fixed(1, 1)

        /**
         * Constructs and primes the fixed payload outside measured invocation boundaries.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            node = PayloadNode(List(semanticsCount) { Semantics(label = UiText.Literal(it.toString())) })
            session = createRuntimeUiSession { PayloadElement(node) }
            session.attach()
            session.frame(constraints)
            verifyWork()
        }

        /**
         * Invalidates only paint so semantics collection must hand off its complete cached payload again.
         */
        public fun changedFrame(): RuntimeUiFrame {
            node.invalidatePaint()
            return session.frame(constraints)
        }

        /**
         * Returns the already committed complete frame as the unchanged-path control.
         */
        public fun cleanFrame(): RuntimeUiFrame = session.frame(constraints)

        /**
         * Proves ordered values, bounds, cached callback work and earlier-frame immutability outside timings.
         */
        public fun verifyWork() {
            val original = cleanFrame()
            val expected = original.semantics.toList()
            check(expected.size == semanticsCount)
            expected.forEachIndexed { index, entry ->
                check(entry.bounds == IntRect(0, 0, 1, 1))
                check(entry.semantics.label == UiText.Literal(index.toString()))
            }
            repeat(10) {
                val paintCalls = node.paintCalls
                val changed = changedFrame()
                check(node.paintCalls == paintCalls + 1 && node.semanticsCalls == 1 && node.measureCalls == 1)
                check(changed.semantics == expected && original.semantics == expected)
                check(cleanFrame() === changed)
            }
        }

        /**
         * Releases the retained owner and verifies exactly one terminal node disposal.
         */
        @TearDown(Level.Trial)
        public fun close() {
            session.close()
            check(node.disposals == 1)
        }
    }

    /**
     * Untimed deterministic checks for every compiled semantic payload size.
     */
    public companion object {
        /**
         * Verifies rebuilt output, clean-frame reuse and close for all ten collection cases.
         */
        public fun verifyWork() {
            for (count in listOf(0, 1, 128, 1_000, 10_000)) {
                val scene = Scene()
                scene.semanticsCount = count
                scene.setUp()
                try {
                    scene.verifyWork()
                } finally {
                    scene.close()
                }
            }
        }
    }

    /**
     * Emits one immutable current payload while keeping geometry and local semantics cached.
     */
    private class PayloadNode(
        private val values: List<Semantics>,
    ) : Node(),
        LifecycleNode,
        MeasureNode,
        PaintNode,
        SemanticsNode {
        var measureCalls = 0
        var paintCalls = 0
        var semanticsCalls = 0
        var disposals = 0

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            measureCalls += 1
            return constraints.constrain(IntSize(1, 1))
        }

        override fun paint(scope: PaintScope) {
            paintCalls += 1
        }

        override fun semantics(scope: SemanticsScope) {
            semanticsCalls += 1
            values.forEach(scope::emit)
        }

        override fun dispose() {
            disposals += 1
        }

        override fun attach() = Unit

        override fun detach() = Unit

        /**
         * Requests another frame without invalidating local semantics or geometry.
         */
        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    /**
     * Static positional declaration retaining exactly one fixture node.
     */
    private class PayloadElement(
        val node: PayloadNode,
    ) : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable type token for this isolated semantics fixture.
         */
        companion object {
            val TYPE: ElementType<PayloadElement, PayloadNode> =
                ElementType(
                    elementClass = PayloadElement::class,
                    nodeClass = PayloadNode::class,
                    validateLocal = { _ -> },
                    createNode = { it.node },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
