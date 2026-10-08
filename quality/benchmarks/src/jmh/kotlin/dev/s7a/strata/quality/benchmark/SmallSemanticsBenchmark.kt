package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
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
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
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
 * Actual-session collection corpus with nonparticipant, zero, singleton and many-emission controls.
 * Ordinary and captured-scope callbacks use identical input/output and escape behavior across runtime sides.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class SmallSemanticsBenchmark {
    /**
     * Completes the declared session frame, including initial lifecycle work for the initial control.
     */
    @Benchmark
    public fun collect(state: Scene): RuntimeUiFrame = state.nextFrame()

    /**
     * Typed operation selected from compiled metadata.
     */
    public enum class Mode {
        Refresh,
        Initial,
        Paint,
        Local,
        Geometry,
        Clean,
        Reorder,
        Remove,
    }

    /**
     * Fixed local callback cardinality; None has no SemanticsNode capability.
     */
    public enum class Cardinality(
        public val emissions: Int,
    ) {
        None(0),
        Zero(0),
        One(1),
        Many(8),
    }

    /**
     * Complete topology and operation controls; mixed shapes cycle through the typed cardinality enum.
     *
     * @property mode work included in each sampled invocation.
     * @property count producer/passive nodes below the additional passive root.
     * @property cardinality fixed capability/emission count, or null for the typed mixed cycle.
     * @property deep chains current children instead of using a broad sibling collection.
     */
    public enum class Workload(
        public val mode: Mode,
        public val count: Int,
        public val cardinality: Cardinality? = null,
        public val deep: Boolean = false,
    ) {
        RefreshNone1000(Mode.Refresh, 1_000, Cardinality.None),
        RefreshZero1000(Mode.Refresh, 1_000, Cardinality.Zero),
        RefreshOne1000(Mode.Refresh, 1_000, Cardinality.One),
        RefreshMany128x8(Mode.Refresh, 128, Cardinality.Many),
        InitialMixed128(Mode.Initial, 128),
        RefreshMixedBroad1000(Mode.Refresh, 1_000),
        RefreshMixedDeep128(Mode.Refresh, 128, deep = true),
        PaintMixed1000(Mode.Paint, 1_000),
        LocalMixed1000(Mode.Local, 1_000),
        GeometryMixed1000(Mode.Geometry, 1_000),
        CleanMixed1000(Mode.Clean, 1_000),
        ReorderMixed1000(Mode.Reorder, 1_000),
        RemoveMixed1000(Mode.Remove, 1_000),
    }

    /**
     * Owner-local fixed declarations and sources, with bounded current nodes and one captured scope per capable node.
     */
    @State(Scope.Thread)
    @Suppress("TooManyFunctions") // One fixed scene owns all operation controls and untimed collection evidence.
    public open class Scene {
        /**
         * Compiled complete-session case.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.RefreshOne1000

        /**
         * Models intentional callback-scope escape without changing its validity.
         */
        @JvmField
        @Param("false", "true")
        public var captured: Boolean = false

        private val normalConstraints = Constraints.fixed(16, 16)
        private val wideConstraints = Constraints.fixed(17, 16)
        private val nodes = ArrayList<PayloadNode>()
        private var session: RuntimeUiSession? = null
        private lateinit var arrangement: MutableState<Arrangement>
        private var specifications = emptyList<Specification>()
        private var rootDeclarations = emptyMap<Arrangement, Element>()
        private var currentConstraints = normalConstraints
        private var alternate = false
        private var callbacks = 0L
        private var created = 0L
        private var disposed = 0L
        private var diagnose = false
        private var entryBuffers = 0L
        private var firstBuffers = 0L
        private var secondBuffers = 0L

        /**
         * Prepares immutable values, keyed arrangements and primed frame output outside timings.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            arrangement = mutableStateOf(Arrangement.Normal)
            specifications =
                List(workload.count) { index ->
                    val cardinality = workload.cardinality ?: Cardinality.entries[index % Cardinality.entries.size]
                    Specification(index, cardinality, values(index, cardinality.emissions, false), values(index, cardinality.emissions, true))
                }
            rootDeclarations = Arrangement.entries.associateWith { order -> declaration(ordered(order)) }
            if (workload.mode != Mode.Initial) {
                session =
                    newSession().also {
                        it.attach()
                        it.frame(normalConstraints)
                    }
            }
        }

        /**
         * Includes only the declared publication/invalidation and actual retained frame operation.
         */
        public fun nextFrame(): RuntimeUiFrame {
            if (workload.mode == Mode.Initial) return initialFrame()
            alternate = alternate.not()
            when (workload.mode) {
                Mode.Refresh -> {
                    nodes.forEach { it.invalidatePhase(DirtyPhase.Semantics) }
                }

                Mode.Paint -> {
                    nodes.first().invalidatePhase(DirtyPhase.Paint)
                }

                Mode.Local -> {
                    val node = nodes.first { it.specification.cardinality == Cardinality.One }
                    node.values = if (alternate) node.specification.changed else node.specification.original
                    node.invalidatePhase(DirtyPhase.Semantics)
                }

                Mode.Geometry -> {
                    currentConstraints = if (alternate) wideConstraints else normalConstraints
                }

                Mode.Reorder -> {
                    arrangement.value = if (alternate) Arrangement.Reverse else Arrangement.Normal
                }

                Mode.Remove -> {
                    arrangement.value = if (alternate) Arrangement.Trimmed else Arrangement.Normal
                }

                Mode.Initial, Mode.Clean -> Unit
            }
            return checkNotNull(session).frame(currentConstraints)
        }

        /**
         * Requires ordered values/bounds, exact callbacks, old-frame immutability and current ownership.
         */
        public fun verifyWork() {
            diagnose = true
            val initial = nextFrame()
            val detached = initial.semantics.toList()
            verifyFrame(initial)
            repeat(10) {
                val before = callbacks
                val previous = session?.frame(currentConstraints)
                val frame = nextFrame()
                verifyFrame(frame)
                verifyCallbacks(before)
                check(initial.semantics == detached)
                if (workload.mode == Mode.Clean) check(frame === previous)
                if (workload.mode != Mode.Initial) check(checkNotNull(session).frame(currentConstraints) === frame)
            }
            check(created - disposed == nodes.size.toLong())
            if (captured) {
                nodes.mapNotNull { it.capturedScope }.forEach { scope ->
                    check(runCatching { scope.emit(Semantics()) }.exceptionOrNull() is IllegalStateException)
                }
            }
            // These observations are outside sampling and expose construction sites without certifying surviving allocations.
            println("SmallSemantics work: $workload captured=$captured callbacks=$callbacks entryBuffers=$entryBuffers firstBuffers=$firstBuffers secondBuffers=$secondBuffers")
            diagnose = false
        }

        private fun verifyCallbacks(before: Long) {
            val expected =
                when (workload.mode) {
                    Mode.Refresh, Mode.Initial, Mode.Geometry -> specifications.count { it.cardinality != Cardinality.None }
                    Mode.Local -> 1
                    Mode.Remove -> if (alternate) 0 else 1
                    Mode.Paint, Mode.Clean, Mode.Reorder -> 0
                }
            check(callbacks - before == expected.toLong()) { "$workload callback count: ${callbacks - before}, expected $expected" }
        }

        private fun verifyFrame(frame: RuntimeUiFrame) {
            val expected =
                ordered(arrangement.value).flatMap { specification ->
                    if (workload.mode == Mode.Local && alternate && specification.cardinality == Cardinality.One && specification.id == firstSingleton()) {
                        specification.changed
                    } else {
                        specification.original
                    }
                }
            check(frame.semantics.map { it.semantics } == expected)
            val width = if (currentConstraints === wideConstraints) 17 else 16
            check(frame.size == IntSize(width, 16))
            check(frame.semantics.all { it.bounds == IntRect(0, 0, width, 16) })
            check(frame.drawCommands.isEmpty())
        }

        private fun firstSingleton(): Int = specifications.first { it.cardinality == Cardinality.One }.id

        private fun initialFrame(): RuntimeUiFrame {
            val initial = newSession()
            try {
                initial.attach()
                return initial.frame(normalConstraints)
            } finally {
                initial.close()
                nodes.clear()
            }
        }

        private fun newSession(): RuntimeUiSession = createRuntimeUiSession { checkNotNull(rootDeclarations[arrangement.value]) }

        private fun ordered(order: Arrangement): List<Specification> =
            when (order) {
                Arrangement.Normal -> specifications
                Arrangement.Reverse -> specifications.asReversed()
                Arrangement.Trimmed -> specifications.dropLast(1)
            }

        private fun declaration(current: List<Specification>): Element {
            val children =
                if (workload.deep) {
                    var child: Element? = null
                    for (specification in current.asReversed()) {
                        child = PayloadElement(this, specification, child?.let { listOf(it) }.orEmpty())
                    }
                    child?.let { listOf(it) }.orEmpty()
                } else {
                    current.map { PayloadElement(this, it, emptyList()) }
                }
            return PayloadElement(this, Specification(-1, Cardinality.None, emptyList(), emptyList()), children)
        }

        private fun values(
            id: Int,
            count: Int,
            changed: Boolean,
        ): List<Semantics> = List(count) { Semantics(label = UiText.Literal("$id:$changed:$it")) }

        private fun create(specification: Specification): PayloadNode {
            val node =
                if (specification.cardinality == Cardinality.None) PayloadNode(this, specification) else Participant(this, specification)
            created += 1
            nodes.add(node)
            return node
        }

        private fun observeBuffer(scope: SemanticsScope): Boolean {
            val field = scope.javaClass.getDeclaredField("values")
            field.isAccessible = true
            return field.get(scope) != null
        }

        private fun emit(
            node: PayloadNode,
            scope: SemanticsScope,
        ) {
            callbacks += 1
            if (captured) node.capturedScope = scope
            if (diagnose && observeBuffer(scope)) entryBuffers += 1
            node.values.forEachIndexed { index, value ->
                scope.emit(value)
                if (diagnose) {
                    if (index == 0 && observeBuffer(scope)) firstBuffers += 1
                    if (index == 1 && observeBuffer(scope)) secondBuffers += 1
                }
            }
        }

        private fun dispose(node: PayloadNode) {
            disposed += 1
            nodes.remove(node)
        }

        /**
         * Closes the current session and drops all callback scopes, declarations and node references.
         */
        @TearDown(Level.Trial)
        public fun close() {
            session?.close()
            check(created == disposed && nodes.isEmpty())
            session = null
            rootDeclarations = emptyMap()
            specifications = emptyList()
        }

        /**
         * Current node with fixed geometry and no native presentation payload.
         */
        private open class PayloadNode(
            private val scene: Scene,
            val specification: Specification,
        ) : Node(),
            MeasureNode,
            LayoutNode,
            PaintNode,
            LifecycleNode {
            var values = specification.original
            var capturedScope: SemanticsScope? = null

            override fun measure(
                scope: MeasureScope,
                constraints: Constraints,
            ): IntSize {
                for (index in 0 until scope.childCount) scope.measureChild(index, constraints)
                return constraints.constrain(IntSize(16, 16))
            }

            override fun layout(scope: LayoutScope) {
                for (index in 0 until scope.childCount) scope.placeChild(index, IntOffset.Zero)
            }

            override fun paint(scope: PaintScope) = Unit

            override fun attach() = Unit

            override fun detach() = Unit

            override fun dispose() {
                capturedScope = null
                values = emptyList()
                scene.dispose(this)
            }

            /**
             * Invalidates only the declared frame phase without constructing new semantic values.
             */
            fun invalidatePhase(phase: DirtyPhase) {
                invalidate(DirtyMask.of(phase))
            }
        }

        /**
         * Actual capability whose scope either stays ordinary or escapes to one bounded current-node slot.
         */
        private class Participant(
            private val scene: Scene,
            specification: Specification,
        ) : PayloadNode(scene, specification),
            SemanticsNode {
            override fun semantics(scope: SemanticsScope) {
                scene.emit(this, scope)
            }
        }

        /**
         * Fixed keyed declaration; each initial session creates fresh owner-confined nodes.
         */
        private class PayloadElement(
            val scene: Scene,
            val specification: Specification,
            children: List<Element>,
        ) : Element(ElementIdentity.Keyed(ElementKey(specification.id)), TYPE, children = children) {
            /**
             * Stable fixture token with no update-time callback or semantic replacement.
             */
            companion object {
                val TYPE: ElementType<PayloadElement, PayloadNode> =
                    ElementType(
                        elementClass = PayloadElement::class,
                        nodeClass = PayloadNode::class,
                        validateLocal = { _ -> },
                        createNode = { it.scene.create(it.specification) },
                        updateNode = { _, _, _ -> DirtyMask.None },
                    )
            }
        }
    }

    /**
     * Generic collector discovers this complete deterministic matrix without a fixture registry.
     */
    public companion object {
        /**
         * Verifies all 26 generated cases with identical ordinary/captured callback inputs.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(SmallSemanticsBenchmark::class.java), setOf("avgt")).size == Workload.entries.size * 2)
            for (workload in Workload.entries) {
                for (captured in listOf(false, true)) {
                    val scene = Scene()
                    scene.workload = workload
                    scene.captured = captured
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

    /**
     * Fixed keyed tree membership; changes publish through real caller-owned state.
     */
    private enum class Arrangement { Normal, Reverse, Trimmed }

    /**
     * Immutable original/changed values and typed capability prepared before sampling.
     */
    private class Specification(
        val id: Int,
        val cardinality: Cardinality,
        val original: List<Semantics>,
        val changed: List<Semantics>,
    )
}
