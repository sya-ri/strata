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
 * Isolates rebuilt-frame semantics handoff with one retained node and immutable local payloads.
 * Separates paint invalidation, local/complete semantics replacement, geometry changes and clean-frame controls.
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
     * Rebuilds the payload after replacing only its first semantic value.
     */
    @Benchmark
    public fun localizedSemantics(state: Scene): RuntimeUiFrame = state.localizedFrame()

    /**
     * Rebuilds the payload after replacing every semantic value.
     */
    @Benchmark
    public fun completeSemantics(state: Scene): RuntimeUiFrame = state.completeFrame()

    /**
     * Changes the bounds of all entries without replacing their semantic values.
     */
    @Benchmark
    public fun changedGeometry(state: Scene): RuntimeUiFrame = state.geometryFrame()

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
        private lateinit var originalValues: List<Semantics>
        private lateinit var localizedValues: List<Semantics>
        private lateinit var completeValues: List<Semantics>
        private val constraints = Constraints.fixed(1, 1)
        private val widerConstraints = Constraints.fixed(2, 1)
        private var localized = false
        private var complete = false
        private var wider = false

        private val currentConstraints: Constraints
            get() = if (wider) widerConstraints else constraints

        /**
         * Constructs and primes the fixed payload outside measured invocation boundaries.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            originalValues = List(semanticsCount) { Semantics(label = UiText.Literal(it.toString())) }
            localizedValues = originalValues.mapIndexed { index, value -> if (index == 0) value.copy(label = UiText.Literal("local")) else value }
            completeValues = originalValues.mapIndexed { index, value -> value.copy(label = UiText.Literal("complete-$index")) }
            node = PayloadNode(originalValues)
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
            return session.frame(currentConstraints)
        }

        /**
         * Returns the already committed complete frame as the unchanged-path control.
         */
        public fun cleanFrame(): RuntimeUiFrame = session.frame(currentConstraints)

        /**
         * Alternates prebuilt payloads outside any declaration or value construction work.
         */
        public fun localizedFrame(): RuntimeUiFrame {
            localized = localized.not()
            node.replaceValues(if (localized) localizedValues else originalValues)
            return session.frame(currentConstraints)
        }

        /**
         * Alternates two fully distinct immutable semantic payloads.
         */
        public fun completeFrame(): RuntimeUiFrame {
            complete = complete.not()
            node.replaceValues(if (complete) completeValues else originalValues)
            return session.frame(currentConstraints)
        }

        /**
         * Alternates fixed root constraints while reusing the local semantic payload.
         */
        public fun geometryFrame(): RuntimeUiFrame {
            wider = wider.not()
            return session.frame(currentConstraints)
        }

        /**
         * Proves ordered values, bounds, cached callback work and earlier-frame immutability outside timings.
         */
        public fun verifyWork() {
            localized = false
            complete = false
            wider = false
            node.replaceValues(originalValues)
            val original = cleanFrame()
            val expected = original.semantics.toList()
            check(expected.size == semanticsCount)
            expected.forEachIndexed { index, entry ->
                check(entry.bounds == IntRect(0, 0, 1, 1))
                check(entry.semantics.label == UiText.Literal(index.toString()))
            }
            repeat(10) {
                val paintCalls = node.paintCalls
                val semanticsCalls = node.semanticsCalls
                val measureCalls = node.measureCalls
                val changed = changedFrame()
                check(node.paintCalls == paintCalls + 1 && node.semanticsCalls == semanticsCalls && node.measureCalls == measureCalls)
                check(changed.semantics == expected && original.semantics == expected)
                check(cleanFrame() === changed)

                for ((changedValues, change) in listOf(localizedValues to ::localizedFrame, completeValues to ::completeFrame)) {
                    val callbacks = node.semanticsCalls
                    val changedSemantics = change()
                    verifyFrame(changedSemantics, changedValues, 1)
                    check(node.semanticsCalls == callbacks + 1)
                    check(original.semantics == expected && cleanFrame() === changedSemantics)
                    verifyFrame(change(), originalValues, 1)
                }
                val callbacks = node.semanticsCalls
                val measures = node.measureCalls
                val changedGeometry = geometryFrame()
                verifyFrame(changedGeometry, originalValues, 2)
                check(node.measureCalls == measures + 1 && node.semanticsCalls == callbacks + 1) {
                    "Geometry callbacks: measure $measures -> ${node.measureCalls}, semantics $callbacks -> ${node.semanticsCalls}"
                }
                check(original.semantics == expected && cleanFrame() === changedGeometry)
                verifyFrame(geometryFrame(), originalValues, 1)
            }
            session.detach()
            check(original.semantics == expected && node.disposals == 0)
            session.attach()
            verifyFrame(cleanFrame(), originalValues, 1)
            check(original.semantics == expected)
        }

        /**
         * Proves a later partial semantics callback failure cannot mutate a committed detached frame.
         * This terminal check is invoked only by untimed workload verification, never by JMH setup.
         */
        public fun verifyFailure() {
            val original = cleanFrame()
            val expected = original.semantics.toList()
            val failure = IllegalStateException("Semantics fixture callback failure")
            node.semanticsFailure = failure
            node.replaceValues(completeValues)
            check(runCatching { cleanFrame() }.exceptionOrNull() === failure)
            check(original.semantics == expected && node.disposals == 1)
        }

        private fun verifyFrame(
            frame: RuntimeUiFrame,
            values: List<Semantics>,
            width: Int,
        ) {
            check(frame.size == IntSize(width, 1))
            check(frame.semantics.size == values.size)
            frame.semantics.forEachIndexed { index, entry ->
                check(entry.bounds == IntRect(0, 0, width, 1))
                check(entry.semantics == values[index])
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
         * Verifies rebuilt output, clean-frame reuse and close for all twenty-five collection cases.
         */
        public fun verifyWork() {
            for (count in listOf(0, 1, 128, 1_000, 10_000)) {
                val scene = Scene()
                scene.semanticsCount = count
                scene.setUp()
                try {
                    scene.verifyWork()
                    scene.verifyFailure()
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
        private var values: List<Semantics>,
    ) : Node(),
        LifecycleNode,
        MeasureNode,
        PaintNode,
        SemanticsNode {
        var measureCalls = 0
        var paintCalls = 0
        var semanticsCalls = 0
        var disposals = 0
        var semanticsFailure: Throwable? = null

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
            semanticsFailure?.let { throw it }
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

        /**
         * Replaces the immutable local payload and requests semantics collection again.
         */
        fun replaceValues(next: List<Semantics>) {
            values = next
            invalidate(DirtyMask.of(DirtyPhase.Semantics))
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
