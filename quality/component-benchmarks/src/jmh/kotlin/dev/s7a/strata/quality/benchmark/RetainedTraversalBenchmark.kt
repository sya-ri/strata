package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
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
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.performance.JmhWorkloadInventory
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
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Fixed broad/deep source, geometry, topology and full-update controls through the real core session.
 * The fixture and callback counters are identical on both runtime archives; no graphics work or native timing is simulated.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class RetainedTraversalBenchmark {
    /**
     * Reuses an unchanged frame after initial content and attachment settle.
     */
    @Benchmark
    public fun idleFrame(state: TraversalState): RuntimeUiFrame = state.next(Change.Idle)

    /**
     * Publishes one of ten independent source revisions and completes its real retained frame.
     */
    @Benchmark
    public fun localizedSourceFrame(state: TraversalState): RuntimeUiFrame = state.next(Change.Source)

    /**
     * Changes one ordinary leaf's geometry without rebuilding source declarations.
     */
    @Benchmark
    public fun localizedGeometryFrame(state: TraversalState): RuntimeUiFrame = state.next(Change.Geometry)

    /**
     * Inserts or removes one focus/semantics participant inside an observed region.
     */
    @Benchmark
    public fun dynamicMembershipFrame(state: TraversalState): RuntimeUiFrame = state.next(Change.DynamicMembership)

    /**
     * Adds or removes an active participant modifier while preserving its component identity.
     */
    @Benchmark
    public fun modifierReplacementFrame(state: TraversalState): RuntimeUiFrame = state.next(Change.ModifierReplacement)

    /**
     * Rebuilds every declaration and changes every primitive's width as a full-tree regression control.
     */
    @Benchmark
    public fun fullUpdateFrame(state: TraversalState): RuntimeUiFrame = state.next(Change.FullUpdate)

    /**
     * Includes complete declaration, ownership, first frame and terminal release costs on an independent tree.
     */
    @Benchmark
    public fun newLifetime(state: TraversalState): RuntimeUiFrame = state.newLifetime()

    /**
     * Six fixed topologies with absent, sparse or dense focus and semantics participation.
     * Deep cases have 64 additional ancestors and one capability-free sibling at each depth.
     */
    public enum class Workload(
        public val leaves: Int,
        public val depth: Int,
        public val participants: Int,
    ) {
        BroadNone(1024, 0, 0),
        BroadFew(1024, 0, 10),
        BroadMany(1024, 0, 1024),
        DeepNone(128, 64, 0),
        DeepFew(128, 64, 10),
        DeepMany(128, 64, 128),
    }

    /**
     * Exact operation selection; each JMH method has one unchanged control matrix.
     */
    public enum class Change {
        Idle,
        Source,
        Geometry,
        DynamicMembership,
        ModifierReplacement,
        FullUpdate,
    }

    /**
     * One active owner and at most one most recent closed lifetime, without historical frame or node retention.
     */
    @State(Scope.Thread)
    public open class TraversalState {
        /**
         * Exact compiled leaf count, depth and participant density selected by JMH.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.BroadNone
        private lateinit var scene: Scene
        private var lifetime: Scene? = null

        /**
         * Establishes one owner-thread session and its initial settled frame outside timed updates.
         */
        @Setup(Level.Trial)
        public fun setup() {
            scene = Scene(workload)
            scene.setup()
        }

        /**
         * Includes publication, invalidation and all required retained work for one fixed operation.
         */
        public fun next(change: Change): RuntimeUiFrame = scene.next(change)

        /**
         * Times a genuinely independent complete lifetime, including final release.
         */
        public fun newLifetime(): RuntimeUiFrame {
            val independent = Scene(workload)
            lifetime = independent
            return try {
                independent.setup()
            } finally {
                independent.close()
            }
        }

        /**
         * Checks live callback work and current ownership outside measured invocations.
         */
        public fun verify(change: Change): Unit = scene.verify(change)

        /**
         * Checks every independent subscription and node was released by the measured lifetime.
         */
        public fun verifyLifetime(): Unit = checkNotNull(lifetime).verifyLifetime()

        /**
         * Releases all current ownership and requires no subscription or primitive survives.
         */
        @TearDown(Level.Trial)
        public fun close() {
            scene.close()
            scene.verifyReleased()
            lifetime?.verifyReleased()
            lifetime = null
        }
    }

    private enum class Phase {
        First,
        Second,
        ;

        fun next(): Phase = if (this == First) Second else First

        val extent: Int get() = if (this == First) 4 else 5
    }

    private data class Payload(
        val phase: Phase,
        val change: Change,
    )

    /**
     * One current session, exact callback counts and identity-indexed current primitives.
     */
    @Suppress("TooManyFunctions") // Update, ownership and untimed work assertions share one worker-owned fixture.
    private class Scene(
        val workload: Workload,
    ) {
        val counts = Counts()
        val nodes: MutableMap<Int, Primitive> = HashMap()
        private val generation = mutableStateOf(Phase.First)
        private val sources = List(10) { Source() }
        private lateinit var session: RuntimeUiSession
        private lateinit var frame: RuntimeUiFrame
        private var phase = Phase.First
        private val constraints = Constraints.fixed(320, 180)
        private var closed = false

        fun setup(): RuntimeUiFrame {
            session = createRuntimeUiSession { description(generation.value) }
            session.attach()
            frame = session.frame(constraints)
            check(session.frame(constraints) === frame)
            return frame
        }

        fun next(change: Change): RuntimeUiFrame {
            counts.clear()
            if (change != Change.Idle) phase = phase.next()
            when (change) {
                Change.Idle -> Unit
                Change.Geometry -> nodes.getValue(workload.leaves).geometry(phase.extent)
                Change.FullUpdate -> generation.value = phase
                else -> sources.first().publish(Payload(phase, change))
            }
            val previous = frame
            frame = session.frame(constraints)
            if (change == Change.Idle) check(previous === frame)
            return frame
        }

        fun verify(change: Change) {
            val added = change == Change.DynamicMembership && phase == Phase.Second
            val modifier = change == Change.ModifierReplacement && phase == Phase.Second
            check(frame.size == IntSize(320, 180) && frame.drawCommands.isEmpty())
            verifySemantics(change, added, modifier)
            check(sources.all { it.active })
            check(nodes.size == primitiveCount() + if (added) 1 else 0)
            check(counts.focusTransitions == 0)
            check(counts.contents == expectedContents(change))
            check(counts.updates == expectedUpdates(change))
            val geometry = expectedGeometry(change, added)
            check(counts.measures == geometry && counts.layouts == geometry)
            check(counts.semantics == expectedSemantics(change, added || modifier))
            check(counts.focusReads == if (change == Change.Idle) 0 else frame.semantics.size)
            check(counts.attaches == if (added) 1 else 0)
            check(counts.disposes == if (change == Change.DynamicMembership && added.not()) 1 else 0)
            check(counts.detaches == counts.disposes)
            check(counts.modifierAttaches == if (modifier) 1 else 0)
            check(counts.modifierDisposes == if (change == Change.ModifierReplacement && modifier.not()) 1 else 0)
            check(counts.modifierDetaches == counts.modifierDisposes)
        }

        private fun expectedContents(change: Change): Int =
            when (change) {
                Change.Idle, Change.Geometry -> 0
                Change.FullUpdate -> sources.size
                else -> 1
            }

        private fun expectedUpdates(change: Change): Int =
            when (change) {
                Change.Idle, Change.Geometry -> 0
                Change.FullUpdate -> primitiveCount()
                else -> 2
            }

        private fun expectedGeometry(
            change: Change,
            added: Boolean,
        ): Int =
            when (change) {
                Change.Idle -> 0
                Change.FullUpdate -> primitiveCount()
                Change.Geometry -> workload.depth + 2
                else -> workload.depth + 3 + if (added) 1 else 0
            }

        private fun expectedSemantics(
            change: Change,
            extra: Boolean,
        ): Int =
            when (change) {
                Change.Idle -> 0
                Change.FullUpdate -> workload.participants
                Change.Geometry -> if (workload.leaves <= workload.participants) 1 else 0
                else -> (if (workload.participants == 0) 0 else 1) + if (extra) 1 else 0
            }

        private fun verifySemantics(
            change: Change,
            added: Boolean,
            modifier: Boolean,
        ) {
            var index = 0
            for (ordinal in 0 until workload.leaves) {
                val width = expectedWidth(ordinal, change)
                if (ordinal == 0 && modifier) requireEntry(index++, "modifier", width)
                if (ordinal < workload.participants) requireEntry(index++, "${ordinal + 1}:$width", width)
                if (ordinal == 0 && added) requireEntry(index++, "${workload.leaves + 1}:4", 4)
            }
            check(index == frame.semantics.size)
        }

        private fun expectedWidth(
            ordinal: Int,
            change: Change,
        ): Int =
            when {
                ordinal < sources.size -> sources[ordinal].value.phase.extent + generation.value.extent - 4
                change == Change.Geometry && ordinal == workload.leaves - 1 -> phase.extent
                else -> generation.value.extent
            }

        private fun requireEntry(
            index: Int,
            label: String,
            width: Int,
        ) {
            val entry = frame.semantics[index]
            check(entry.bounds == IntRect(0, 0, width, 4))
            check(entry.semantics == Semantics(label = UiText.Literal(label)))
        }

        fun verifyLifetime() {
            verifyReleased()
            check(counts.contents == sources.size)
            check(counts.attaches == primitiveCount() && counts.disposes == primitiveCount())
            check(counts.detaches == counts.disposes)
            check(counts.measures == primitiveCount() && counts.layouts == primitiveCount())
            check(counts.semantics == workload.participants && frame.semantics.size == workload.participants)
            check(counts.modifierAttaches == 0 && counts.modifierDisposes == 0)
            verifySemantics(Change.Idle, added = false, modifier = false)
        }

        fun verifyReleased() {
            check(closed && nodes.isEmpty() && sources.none { it.active })
        }

        fun close() {
            if (closed) return
            closed = true
            session.close()
        }

        private fun primitiveCount(): Int = workload.leaves + sources.size + workload.depth * 2 + 1

        private fun description(generation: Phase): Element {
            val children =
                List(workload.leaves) { ordinal ->
                    if (ordinal < sources.size) {
                        evaluateComponentTree {
                            Observe(sources[ordinal], key = ElementKey(10_000 + ordinal)) { payload ->
                                counts.contents += 1
                                element(observedDescription(ordinal, payload, generation))
                            }
                        }
                    } else {
                        primitive(ordinal + 1, ordinal < workload.participants, generation.extent)
                    }
                }
            var root: Element = primitive(0, width = generation.extent, children = children)
            repeat(workload.depth) { depth ->
                root = primitive(30_000 + depth * 2, width = generation.extent, children = listOf(root, primitive(30_001 + depth * 2, width = generation.extent)))
            }
            return root
        }

        private fun observedDescription(
            ordinal: Int,
            payload: Payload,
            generation: Phase,
        ): Element {
            val width = payload.phase.extent + generation.extent - 4
            val modifier =
                if (payload.change == Change.ModifierReplacement && payload.phase == Phase.Second) {
                    Modifier.Empty.then(ParticipantModifierElement(this))
                } else {
                    Modifier.Empty
                }
            val leaf = primitive(ordinal + 1, ordinal < workload.participants, width, modifier = modifier)
            val children =
                if (payload.change == Change.DynamicMembership && payload.phase == Phase.Second) {
                    listOf(leaf, primitive(workload.leaves + 1, participant = true, width = 4))
                } else {
                    listOf(leaf)
                }
            return primitive(20_000 + ordinal, width = width, children = children)
        }

        private fun primitive(
            id: Int,
            participant: Boolean = false,
            width: Int = 4,
            children: List<Element> = emptyList(),
            modifier: Modifier = Modifier.Empty,
        ): PrimitiveElement = PrimitiveElement(this, id, participant, width, children, modifier)
    }

    /**
     * Fixed fixture-only counters perform no timing or allocation instrumentation.
     */
    private class Counts {
        var contents = 0
        var updates = 0
        var measures = 0
        var layouts = 0
        var semantics = 0
        var focusReads = 0
        var focusTransitions = 0
        var attaches = 0
        var detaches = 0
        var disposes = 0
        var modifierAttaches = 0
        var modifierDetaches = 0
        var modifierDisposes = 0

        fun clear() {
            contents = 0
            updates = 0
            measures = 0
            layouts = 0
            semantics = 0
            focusReads = 0
            focusTransitions = 0
            attaches = 0
            detaches = 0
            disposes = 0
            modifierAttaches = 0
            modifierDetaches = 0
            modifierDisposes = 0
        }
    }

    private class Source : StateSource<Payload> {
        private var snapshot = StateSnapshot(StateRevision(0), Payload(Phase.First, Change.Source))
        private var observer: ((StateSnapshot<Payload>) -> Unit)? = null
        val active: Boolean get() = observer != null
        val value: Payload get() = snapshot.value

        override fun subscribe(observer: (StateSnapshot<Payload>) -> Unit): StateSubscription<Payload> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(snapshot) { this.observer = null }
        }

        fun publish(payload: Payload) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), payload)
            observer?.invoke(snapshot)
        }
    }

    private class PrimitiveElement(
        val scene: Scene,
        val id: Int,
        participant: Boolean,
        val width: Int,
        children: List<Element>,
        modifier: Modifier,
    ) : Element(ElementIdentity.Keyed(ElementKey(id)), if (participant) ParticipantType else PlainType, children, modifier) {
        companion object {
            private val PlainType = ElementType(PrimitiveElement::class, Primitive::class, {}, { Primitive(it).also { node -> it.scene.nodes[it.id] = node } }, { _, current, node -> node.update(current) })
            private val ParticipantType = ElementType(PrimitiveElement::class, Participant::class, {}, { Participant(it).also { node -> it.scene.nodes[it.id] = node } }, { _, current, node -> node.update(current) })
        }
    }

    private open class Primitive(
        var description: PrimitiveElement,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        LifecycleNode {
        protected var width = description.width
        protected val counts: Counts get() = description.scene.counts

        fun update(description: PrimitiveElement): DirtyMask {
            this.description = description
            width = description.width
            counts.updates += 1
            return DirtyMask.All
        }

        fun geometry(width: Int) {
            this.width = width
            invalidate(DirtyMask.of(DirtyPhase.Measure))
        }

        override fun attach() {
            counts.attaches += 1
        }

        override fun detach() {
            counts.detaches += 1
        }

        override fun dispose() {
            counts.disposes += 1
            val nodes = description.scene.nodes
            check(nodes[description.id] === this)
            nodes.remove(description.id)
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            counts.measures += 1
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            return constraints.constrain(IntSize(width, 4))
        }

        override fun layout(scope: LayoutScope) {
            counts.layouts += 1
            for (index in 0 until scope.childCount) scope.placeChild(index, IntOffset.Zero)
        }
    }

    private class Participant(
        description: PrimitiveElement,
    ) : Primitive(description),
        FocusTargetNode,
        SemanticsNode {
        override val acceptsFocus: Boolean
            get() {
                counts.focusReads += 1
                return true
            }

        override fun onFocusChanged(focused: Boolean) {
            counts.focusTransitions += 1
        }

        override fun semantics(scope: SemanticsScope) {
            counts.semantics += 1
            scope.emit(Semantics(label = UiText.Literal("${description.id}:$width")))
        }
    }

    private class ParticipantModifierElement(
        val scene: Scene,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> = Type

        companion object {
            private val Type = ModifierNodeType(ParticipantModifierElement::class, ParticipantModifier::class, {}, { ParticipantModifier(it.scene) }, { _, _, _ -> DirtyMask.None })
        }
    }

    private class ParticipantModifier(
        private val scene: Scene,
    ) : ModifierNode(),
        LifecycleNode,
        FocusTargetNode,
        SemanticsNode {
        override val acceptsFocus: Boolean
            get() {
                scene.counts.focusReads += 1
                return true
            }

        override fun onFocusChanged(focused: Boolean) {
            scene.counts.focusTransitions += 1
        }

        override fun semantics(scope: SemanticsScope) {
            scene.counts.semantics += 1
            scope.emit(Semantics(label = UiText.Literal("modifier")))
        }

        override fun attach() {
            scene.counts.modifierAttaches += 1
        }

        override fun detach() {
            scene.counts.modifierDetaches += 1
        }

        override fun dispose() {
            scene.counts.modifierDisposes += 1
        }
    }

    /**
     * Untimed callback, geometry, semantics and terminal assertions are invoked by the shared generated-fixture verifier.
     */
    public companion object {
        /**
         * Exercises all 42 fixed cases on the loaded runtime, with repeated source/topology alternation and independent lifetimes.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RetainedTraversalBenchmark::class.java), setOf("avgt")).size == 42)
            Workload.entries.forEach { workload ->
                val state = TraversalState()
                state.workload = workload
                state.setup()
                try {
                    Change.entries.forEach { change ->
                        repeat(128) {
                            state.next(change)
                            state.verify(change)
                        }
                    }
                    repeat(3) {
                        state.newLifetime()
                        state.verifyLifetime()
                    }
                } finally {
                    state.close()
                }
            }
        }
    }
}
