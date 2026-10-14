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
import dev.s7a.strata.node.FrameTimeNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.StateObservation
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
 * Frozen operation bookkeeping and real-session controls, with setup and deterministic work checks outside sampling.
 * Results are per batch: many-session, nested and high-rate inputs must retain their declared operation counts.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class OperationGuardBenchmark {
    /**
     * Completes the declared guard or frame batch and returns a consumed output checksum.
     */
    @Benchmark
    public fun operation(state: Scene): Long = state.runBatch()

    /**
     * Typed control behavior selected before fixture construction.
     */
    public enum class Kind {
        /**
         * Paired operation entry and exit without a state write.
         */
        Guard,

        /**
         * Paired guards with a real caller-owned state assignment.
         */
        Mutation,

        /**
         * Untimed clean retained frames.
         */
        Clean,

        /**
         * Explicitly timed clean retained frames with a time-aware node.
         */
        Timed,

        /**
         * One shared state publication followed by every affected session frame.
         */
        Dirty,

        /**
         * Time callbacks synchronously enter the next independent session.
         */
        Nested,
    }

    /**
     * Complete generated matrix; count is guard depth or session count, and repeats is frames per session.
     *
     * @property kind operation or frame behavior fixed before setup.
     * @property count active guard depth or independent session count.
     * @property repeats frame calls per session in one measured batch.
     */
    public enum class Workload(
        public val kind: Kind,
        public val count: Int,
        public val repeats: Int = 1,
    ) {
        Guard1(Kind.Guard, 1),
        Guard2(Kind.Guard, 2),
        Guard8(Kind.Guard, 8),
        Mutation1(Kind.Mutation, 1),
        Mutation8(Kind.Mutation, 8),
        Clean1(Kind.Clean, 1),
        Timed1(Kind.Timed, 1),
        Clean128(Kind.Clean, 128),
        Timed128(Kind.Timed, 128),

        /**
         * One owner repeatedly consumes the same completed frame in a headless loop.
         */
        HighRate10000(Kind.Clean, 1, 10_000),

        Dirty1(Kind.Dirty, 1),
        Dirty128(Kind.Dirty, 128),
        Nested2(Kind.Nested, 2),
        Nested8(Kind.Nested, 8),
    }

    /**
     * One execution-owner-confined corpus per worker, primed before timed invocations.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Fixed batch topology decoded from generated JMH enum metadata.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.Clean1

        private val counts = Counts()
        private val constraints = Constraints.fixed(16, 16)
        private var guards = emptyList<StateObservation>()
        private var sessions = emptyList<RuntimeUiSession>()
        private var nodes = emptyList<FrameNode>()
        private var initialFrames = emptyList<RuntimeUiFrame>()
        private lateinit var mutation: MutableState<Long>
        private lateinit var presentation: MutableState<Presentation>
        private var tick = 0L

        /**
         * Constructs fixed declarations, caller state and settled output without timed declaration allocation.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            mutation = mutableStateOf(0L)
            presentation = mutableStateOf(Presentation.First)
            if (workload.kind == Kind.Guard || workload.kind == Kind.Mutation) {
                guards = List(workload.count) { StateObservation({}, {}, {}, { counts.validations += 1 }) }
            } else {
                nodes = List(workload.count) { FrameNode(counts) }
                sessions =
                    nodes.map { node ->
                        val first = FrameElement(node, Presentation.First)
                        val second = FrameElement(node, Presentation.Second)
                        createRuntimeUiSession {
                            counts.evaluations += 1
                            when (presentation.value) {
                                Presentation.First -> first
                                Presentation.Second -> second
                            }
                        }
                    }
                if (workload.kind == Kind.Nested) {
                    nodes.forEachIndexed { index, node -> node.nextSession = sessions.getOrNull(index + 1) }
                }
                sessions.forEach { it.attach() }
                initialFrames = sessions.map { it.frame(constraints) }
            }
        }

        /**
         * Includes guard pairing, optional publication and all declared frame calls in each measured batch.
         */
        public fun runBatch(): Long {
            tick += 1
            if (guards.isNotEmpty()) {
                guards.forEach(StateObservation::enterOperation)
                try {
                    if (workload.kind == Kind.Mutation) mutation.value += 1
                } finally {
                    for (index in guards.lastIndex downTo 0) guards[index].leaveOperation()
                }
                return mutation.value + tick
            }
            if (workload.kind == Kind.Dirty) {
                presentation.value =
                    when (presentation.value) {
                        Presentation.First -> Presentation.Second
                        Presentation.Second -> Presentation.First
                    }
            }
            var checksum = 0L
            if (workload.kind == Kind.Nested) {
                checksum = consume(sessions.first().frame(constraints, FrameTime(tick)))
            } else {
                repeat(workload.repeats) {
                    for (session in sessions) {
                        val frame =
                            if (workload.kind == Kind.Timed) session.frame(constraints, FrameTime(tick)) else session.frame(constraints)
                        checksum += consume(frame)
                    }
                }
            }
            return checksum
        }

        /**
         * Requires exact callbacks, current ownership and immutable outputs over ten untimed batches.
         */
        public fun verifyWork() {
            val batches = 10L
            repeat(batches.toInt()) {
                runBatch()
                verifyOutput()
            }
            when (workload.kind) {
                Kind.Guard -> check(counts.validations == 0L && mutation.value == 0L)
                Kind.Mutation -> check(counts.validations == batches * workload.count && mutation.value == batches)
                else -> verifyFrameCounts(batches)
            }
        }

        /**
         * Releases nodes, caller observations and every fixture link on the owning worker.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                sessions.asReversed().forEach { it.close() }
                guards.asReversed().forEach(StateObservation::close)
                check(counts.detached == sessions.size.toLong() && counts.disposed == sessions.size.toLong())
            } finally {
                nodes.forEach { it.nextSession = null }
                guards = emptyList()
                sessions = emptyList()
                nodes = emptyList()
                initialFrames = emptyList()
            }
        }

        private fun verifyFrameCounts(batches: Long) {
            val expectedChanges = if (workload.kind == Kind.Dirty) batches else 0L
            val expectedTimes = if (workload.kind == Kind.Timed || workload.kind == Kind.Nested) batches else 0L
            check(counts.evaluations == workload.count * (expectedChanges + 1))
            check(counts.measures == workload.count.toLong())
            check(counts.paints == workload.count * (expectedChanges + 1))
            check(counts.semantics == counts.paints && counts.times == expectedTimes * workload.count)
            check(counts.attached == workload.count.toLong() && counts.disposed == 0L)
        }

        private fun verifyOutput() {
            sessions.forEachIndexed { index, session ->
                val frame = session.frame(constraints)
                check(frame.size == IntSize(16, 16))
                check(frame.drawCommands.single() == DrawCommand.FillRectangle(IntRect(0, 0, 16, 16), presentation.value.color))
                check(
                    frame.semantics
                        .single()
                        .semantics
                        .label == presentation.value.label,
                )
                if (workload.kind != Kind.Dirty) {
                    check(frame === initialFrames[index])
                }
                check(initialFrames[index].drawCommands.single() == DrawCommand.FillRectangle(IntRect(0, 0, 16, 16), Presentation.First.color))
                check(
                    initialFrames[index]
                        .semantics
                        .single()
                        .semantics
                        .label == Presentation.First.label,
                )
            }
        }

        private fun consume(frame: RuntimeUiFrame): Long = frame.size.width.toLong() + frame.drawCommands.size + frame.semantics.size
    }

    /**
     * Generic collector discovers this static deterministic verifier without fixture-specific build registration.
     */
    public companion object {
        /**
         * Checks every compiled workload's callback counts and terminal ownership outside the timing interval.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(OperationGuardBenchmark::class.java), setOf("avgt")).size == Workload.entries.size)
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
    }

    /**
     * Scalar evidence only; the fixture never appends historical nodes or completed frames.
     */
    private class Counts {
        var validations = 0L
        var evaluations = 0L
        var measures = 0L
        var paints = 0L
        var semantics = 0L
        var times = 0L
        var attached = 0L
        var detached = 0L
        var disposed = 0L
    }

    /**
     * Fixed presentation alternatives used by the dirty control's authoritative caller-owned state.
     */
    private enum class Presentation(
        val color: ArgbColor,
        val label: UiText,
    ) {
        First(ArgbColor(0xFFFF0000.toInt()), UiText.Literal("First")),
        Second(ArgbColor(0xFF0000FF.toInt()), UiText.Literal("Second")),
    }

    /**
     * One stable node per session with paint/semantics changes and unchanged geometry.
     */
    private class FrameNode(
        private val counts: Counts,
    ) : Node(),
        MeasureNode,
        PaintNode,
        SemanticsNode,
        FrameTimeNode,
        LifecycleNode {
        var presentation = Presentation.First
        var nextSession: RuntimeUiSession? = null
        private val constraints = Constraints.fixed(16, 16)

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            counts.measures += 1
            return constraints.constrain(IntSize(16, 16))
        }

        override fun paint(scope: PaintScope) {
            counts.paints += 1
            scope.fillRectangle(IntRect(0, 0, 16, 16), presentation.color)
        }

        override fun semantics(scope: SemanticsScope) {
            counts.semantics += 1
            scope.emit(Semantics(label = presentation.label))
        }

        override fun onFrame(time: FrameTime) {
            counts.times += 1
            nextSession?.frame(constraints, time)
        }

        override fun attach() {
            counts.attached += 1
        }

        override fun detach() {
            counts.detached += 1
        }

        override fun dispose() {
            counts.disposed += 1
            nextSession = null
        }
    }

    /**
     * Immutable declarations prepared before sampling; retained node identity is session-local.
     */
    private class FrameElement(
        val node: FrameNode,
        val presentation: Presentation,
    ) : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable typed fixture token.
         */
        companion object {
            val TYPE: ElementType<FrameElement, FrameNode> =
                ElementType(
                    elementClass = FrameElement::class,
                    nodeClass = FrameNode::class,
                    validateLocal = { _ -> },
                    createNode = { it.node },
                    updateNode = { previous, current, node ->
                        node.presentation = current.presentation
                        if (previous.presentation == current.presentation) DirtyMask.None else DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)
                    },
                )
        }
    }
}
