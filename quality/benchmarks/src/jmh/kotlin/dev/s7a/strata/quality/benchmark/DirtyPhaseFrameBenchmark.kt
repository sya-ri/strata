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
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
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
import org.openjdk.jmh.infra.Blackhole

/**
 * Complete retained invalidation and frame operations, including ordered command and semantics consumption.
 * The seventy-two cases retain all phase, fanout, leaf-count, topology and independent control combinations.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class DirtyPhaseFrameBenchmark {
    /**
     * Includes public invalidation or the declared control, a complete frame and its ordered output consumption.
     */
    @Benchmark
    public fun frame(
        state: Scene,
        blackhole: Blackhole,
    ) {
        val frame = state.nextFrame()
        frame.drawCommands.forEach(blackhole::consume)
        frame.semantics.forEach(blackhole::consume)
    }

    /**
     * The complete set of operations; masks and immutable descriptions are prepared outside timing.
     * @property phase local phase requested by dirty operations, or null for an independent control.
     * @property allLeaves whether every current leaf is invalidated.
     */
    public enum class Operation(
        public val phase: DirtyPhase? = null,
        public val allLeaves: Boolean = false,
    ) {
        /**
         * First-leaf measurement and ancestor expansion.
         */
        MeasureFirst(DirtyPhase.Measure),

        /**
         * Every leaf requests measurement.
         */
        MeasureAll(DirtyPhase.Measure, true),

        /**
         * First-leaf layout and downstream work.
         */
        LayoutFirst(DirtyPhase.Layout),

        /**
         * Every leaf requests layout.
         */
        LayoutAll(DirtyPhase.Layout, true),

        /**
         * First-leaf paint only.
         */
        PaintFirst(DirtyPhase.Paint),

        /**
         * Every leaf requests paint only.
         */
        PaintAll(DirtyPhase.Paint, true),

        /**
         * First-leaf semantics only.
         */
        SemanticsFirst(DirtyPhase.Semantics),

        /**
         * Every leaf requests semantics only.
         */
        SemanticsAll(DirtyPhase.Semantics, true),

        /**
         * Unchanged complete-frame control.
         */
        Clean,

        /**
         * Public empty-mask invalidation control.
         */
        None,

        /**
         * Only the root constraint changes.
         */
        Constraint,

        /**
         * Every leaf is structurally replaced under retained ancestors.
         */
        Structure,
    }

    /**
     * One primed public session per JMH worker, with bounded current nodes and scalar lifetime counters.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Complete operation inventory, including all adverse/control paths.
         */
        @JvmField
        @Param
        public var operation: Operation = Operation.Clean

        /**
         * Fixed original leaf counts; actual ancestors are counted separately.
         */
        @JvmField
        @Param("1", "64", "1024")
        public var leaves: Int = 1

        /**
         * Zero or three additional retained ancestors above the leaf group.
         */
        @JvmField
        @Param("0", "3")
        public var depth: Int = 0

        private val alternate = mutableStateOf(false)
        private val nodes = LinkedHashMap<Int, ProbeNode>()
        private val calls = LongArray(4)
        private val constraints = Constraints.fixed(32, 32)
        private val widerConstraints = Constraints.fixed(33, 32)
        private var wider = false
        private var disposed = 0L
        private var attached = 0L
        private var detached = 0L
        private var activeAttachments = 0
        private var attachmentStarts = 0L
        private var attachmentEnds = 0L
        private lateinit var session: RuntimeUiSession
        private var mask = DirtyMask.None

        private val currentConstraints: Constraints
            get() = if (wider) widerConstraints else constraints

        /**
         * Prepares immutable input descriptions, attaches and commits the initial frame outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            mask = operation.phase?.let { DirtyMask.of(it) } ?: DirtyMask.None
            val original = description(false)
            val replacement = description(true)
            session = createRuntimeUiSession { if (alternate.value) replacement else original }
            session.attach()
            verifyPresentation(session.frame(constraints))
            check(nodes.size == leaves + depth + 1)
            check(activeAttachments == nodes.size)
        }

        /**
         * Executes the complete operation on the current live nodes, never a private pipeline or mask-only loop.
         */
        public fun nextFrame(): RuntimeUiFrame {
            when (operation) {
                Operation.Clean -> {
                    Unit
                }

                Operation.None -> {
                    checkNotNull(nodes[0]).dirty(DirtyMask.None)
                }

                Operation.Constraint -> {
                    wider = wider.not()
                }

                Operation.Structure -> {
                    alternate.value = alternate.value.not()
                }

                else -> {
                    val count = if (operation.allLeaves) leaves else 1
                    for (index in 0 until count) checkNotNull(nodes[index]).dirty(mask)
                }
            }
            return session.frame(currentConstraints)
        }

        /**
         * Proves exact real callback counts, independent values/geometry, old frames and bounded current ownership.
         */
        @Suppress("LongMethod") // Keep the independent whole-operation work and ownership oracle at one untimed boundary.
        public fun verifyWork(monitoring: Boolean) {
            val original = session.frame(currentConstraints)
            val oldCommands = original.drawCommands.toList()
            val oldSemantics = original.semantics.toList()
            val monitor = if (monitoring) session.startRenderMonitoring() else null
            try {
                repeat(3) {
                    monitor?.checkpoint()
                    val before = calls.copyOf()
                    val beforeDisposed = disposed
                    val frame = nextFrame()
                    val expected = expectedCalls()
                    check(calls.indices.all { calls[it] - before[it] == expected[it] }) {
                        "Unexpected $operation/$leaves/$depth callbacks: ${calls.indices.map { calls[it] - before[it] }} versus ${expected.toList()}"
                    }
                    monitor?.snapshot()?.let { snapshot ->
                        val metrics = listOf(UiRenderMetric.Measure, UiRenderMetric.Layout, UiRenderMetric.Paint, UiRenderMetric.Semantics)
                        check(metrics.indices.all { snapshot.counts[metrics[it]] == expected[it] })
                        check(snapshot.overflowed.not())
                        check(snapshot.nodes.count { it.retired.not() } == leaves + depth + 1)
                    }
                    check(disposed - beforeDisposed == if (operation == Operation.Structure) leaves.toLong() else 0L)
                    check(nodes.size == leaves + depth + 1)
                    verifyPresentation(frame)
                    check(original.drawCommands == oldCommands && original.semantics == oldSemantics)
                    check(session.frame(currentConstraints) === frame)
                    if (operation == Operation.Clean || operation == Operation.None) check(frame === original)
                }
            } finally {
                monitor?.close()
            }
            session.detach()
            check(activeAttachments == 0 && attachmentEnds == attachmentStarts)
            check(detached == disposed)
            session.attach()
            check(activeAttachments == nodes.size)
            verifyPresentation(session.frame(currentConstraints))
            check(original.drawCommands == oldCommands && original.semantics == oldSemantics)
        }

        /**
         * Verifies failure cleanup against a literal earlier frame without donating successful work to another attempt.
         */
        public fun verifyFailure(phase: DirtyPhase) {
            val original = session.frame(currentConstraints)
            val commands = original.drawCommands.toList()
            val semantics = original.semantics.toList()
            val failure = IllegalStateException("Dirty-phase fixture failure")
            checkNotNull(nodes[0]).failure = phase to failure
            checkNotNull(nodes[0]).dirty(DirtyMask.All)
            check(runCatching { session.frame(currentConstraints) }.exceptionOrNull() === failure)
            check(nodes.isEmpty() && detached == attached && activeAttachments == 0 && attachmentEnds == attachmentStarts)
            check(original.drawCommands == commands && original.semantics == semantics)
        }

        private fun description(replacement: Boolean): Element {
            var current: Element = ProbeElement(this, -1, Role.Grid, List(leaves) { ProbeElement(this, it, Role.Leaf, emptyList(), replacement) })
            repeat(depth) { current = ProbeElement(this, -it - 2, Role.Wrapper, listOf(current)) }
            return current
        }

        private fun expectedCalls(): LongArray {
            val ancestors = depth + 1L
            val targets = if (operation.allLeaves) leaves.toLong() else 1L
            val all = leaves + ancestors
            return when (operation) {
                Operation.Clean, Operation.None -> longArrayOf(0, 0, 0, 0)
                Operation.Constraint, Operation.Structure -> longArrayOf(all, all, all, all)
                Operation.MeasureFirst, Operation.MeasureAll -> longArrayOf(targets + ancestors, targets + ancestors, targets + ancestors, targets + ancestors)
                Operation.LayoutFirst, Operation.LayoutAll -> longArrayOf(0, targets, targets, targets)
                Operation.PaintFirst, Operation.PaintAll -> longArrayOf(0, 0, targets, 0)
                Operation.SemanticsFirst, Operation.SemanticsAll -> longArrayOf(0, 0, 0, targets)
            }
        }

        private fun verifyPresentation(frame: RuntimeUiFrame) {
            val ids = (-depth - 1..-1).map { it to Role.Wrapper } + (0 until leaves).map { it to Role.Leaf }
            check(frame.size == IntSize(if (wider) 33 else 32, 32))
            val expectedCommands = ids.map { (id, role) -> DrawCommand.FillRectangle(bounds(id, role), color(id, role)) }
            val expectedSemantics = ids.map { (id, role) -> SemanticsEntry(bounds(id, role), Semantics(label = UiText.Literal(id.toString()))) }
            check(frame.drawCommands == expectedCommands && frame.semantics == expectedSemantics)
            val image = rasterizeHeadless(frame.drawCommands, frame.size)
            for (y in 0 until 32) {
                for (x in 0 until frame.size.width) {
                    val id = y * 32 + x
                    val expected = if (x < 32 && id < leaves) color(id, Role.Leaf).value else color(-1, Role.Grid).value
                    check(image.argbAt(x, y) == expected)
                }
            }
        }

        private fun bounds(
            id: Int,
            role: Role,
        ): IntRect =
            if (role != Role.Leaf) {
                IntRect(0, 0, if (id == -depth - 1 && wider) 33 else 32, 32)
            } else {
                IntRect(id % 32, id / 32, id % 32 + 1, id / 32 + 1)
            }

        private fun color(
            id: Int,
            role: Role,
        ): ArgbColor = ArgbColor(if (role == Role.Leaf) 0xFF000000.toInt() or (id * 7919 and 0x00FFFFFF) else 0xFF112233.toInt())

        /**
         * Naturally releases every current node and rejects any dangling runtime binding.
         */
        @TearDown(Level.Trial)
        public fun close() {
            val current = nodes.values.toList()
            session.close()
            check(nodes.isEmpty() && detached == attached && activeAttachments == 0 && attachmentEnds == attachmentStarts)
            check(current.all { runCatching { it.dirty(DirtyMask.None) }.exceptionOrNull() is IllegalStateException })
        }

        /**
         * Owner-confined independently counted primitive; current-node map replacement discards old generations.
         */
        private class ProbeNode(
            private val scene: Scene,
            private val id: Int,
            private val role: Role,
        ) : Node(),
            MeasureNode,
            LayoutNode,
            PaintNode,
            SemanticsNode,
            SessionAttachmentNode {
            var failure: Pair<DirtyPhase, Throwable>? = null
            private var attachmentActive = false

            override fun measure(
                scope: MeasureScope,
                constraints: Constraints,
            ): IntSize {
                scene.calls[0] += 1
                fail(DirtyPhase.Measure)
                val childConstraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
                for (index in 0 until scope.childCount) scope.measureChild(index, childConstraints)
                return constraints.constrain(if (role == Role.Leaf) IntSize(1, 1) else IntSize(32, 32))
            }

            override fun layout(scope: LayoutScope) {
                scene.calls[1] += 1
                fail(DirtyPhase.Layout)
                for (index in 0 until scope.childCount) scope.placeChild(index, if (role == Role.Grid) IntOffset(index % 32, index / 32) else IntOffset.Zero)
            }

            override fun paint(scope: PaintScope) {
                scene.calls[2] += 1
                fail(DirtyPhase.Paint)
                scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), scene.color(id, role))
            }

            override fun semantics(scope: SemanticsScope) {
                scene.calls[3] += 1
                fail(DirtyPhase.Semantics)
                scope.emit(Semantics(label = UiText.Literal(id.toString())))
            }

            override fun attach() {
                scene.attached += 1
                sessionAttached()
            }

            override fun detach() {
                scene.detached += 1
                sessionDetached()
            }

            override fun sessionAttached() {
                if (attachmentActive.not()) {
                    attachmentActive = true
                    scene.activeAttachments += 1
                    scene.attachmentStarts += 1
                }
            }

            override fun sessionDetached() {
                if (attachmentActive) {
                    attachmentActive = false
                    scene.activeAttachments -= 1
                    scene.attachmentEnds += 1
                }
            }

            override fun dispose() {
                scene.disposed += 1
                if (scene.nodes[id] === this) scene.nodes.remove(id)
            }

            /**
             * Uses the protected public node invalidation boundary.
             */
            fun dirty(mask: DirtyMask) = invalidate(mask)

            private fun fail(phase: DirtyPhase) {
                failure?.let { if (it.first == phase) throw it.second }
            }
        }

        /**
         * Prebuilt immutable descriptions; only a structural control changes the typed leaf key.
         */
        private class ProbeElement(
            val scene: Scene,
            val id: Int,
            val role: Role,
            children: List<Element>,
            replacement: Boolean = false,
        ) : Element(ElementIdentity.Keyed(ElementKey(Key(id, replacement))), TYPE, children) {
            /**
             * Stable hooks create nodes only through the real retained lifecycle.
             */
            companion object {
                val TYPE: ElementType<ProbeElement, ProbeNode> =
                    ElementType(
                        elementClass = ProbeElement::class,
                        nodeClass = ProbeNode::class,
                        validateLocal = { _ -> },
                        createNode = { element -> ProbeNode(element.scene, element.id, element.role).also { element.scene.nodes[element.id] = it } },
                        updateNode = { _, _, _ -> DirtyMask.None },
                    )
            }
        }

        /**
         * Typed structural identity; ancestors retain the original key.
         */
        private data class Key(
            val id: Int,
            val replacement: Boolean,
        )

        private enum class Role {
            Wrapper,
            Grid,
            Leaf,
        }
    }

    /**
     * Complete untimed controls run on both actual runtime archives before either timing mode.
     */
    public companion object {
        /**
         * Independently admits every original case and all four callback failure phases.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(DirtyPhaseFrameBenchmark::class.java, SemanticsFrameBenchmark::class.java), setOf("avgt")).size == 97)
            for (operation in Operation.entries) {
                for (leaves in listOf(1, 64, 1024)) {
                    for (depth in listOf(0, 3)) {
                        verifyScene(operation, leaves, depth)
                    }
                }
            }
            for (phase in DirtyPhase.entries) {
                val scene = Scene()
                scene.depth = 3
                scene.leaves = 64
                scene.setup()
                try {
                    scene.verifyFailure(phase)
                } finally {
                    scene.close()
                }
            }
        }

        private fun verifyScene(
            operation: Operation,
            leaves: Int,
            depth: Int,
        ) {
            for (monitoring in listOf(false, true)) {
                val scene = Scene()
                scene.operation = operation
                scene.leaves = leaves
                scene.depth = depth
                scene.setup()
                try {
                    scene.verifyWork(monitoring)
                } finally {
                    scene.close()
                }
            }
        }
    }
}
