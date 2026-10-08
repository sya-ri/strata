package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.performance.JmhWorkloadInventory
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
 * Measures clean-frame focused input independently of retained frame preparation.
 * The fixed matrix covers small chains, absent focus, consumption, typed text and large cyclic traversal without a Cartesian product.
 * Events, nodes and declarations are prepared outside timing; each operation includes only the session input boundary and dispatch.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class FocusedInputBenchmark {
    /**
     * Delivers one prebuilt event through the committed retained tree.
     */
    @Benchmark
    public fun dispatch(scene: Scene): InputResult = scene.dispatch()

    /**
     * One owner-thread session for one fixed input case.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Frozen cases shared by both measured runtime archives.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.PressNoModifiers

        private lateinit var fixture: Fixture

        /**
         * Prepares the tree, immutable events and independent callback/focus oracle outside measurement.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = Fixture(workload)
            fixture.verifyWork()
        }

        /**
         * Returns the observed result so JMH consumes it without allocating a trace.
         */
        public fun dispatch(): InputResult = fixture.dispatch()

        /**
         * Requires exact terminal disposal without adding close latency to the dispatch interval.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Fixed coverage rather than independent parameter dimensions; owner counts include the structural root.
     * None/Few/Many mean zero, at most three, or every nonstructural owner accepts traversal.
     */
    public enum class Workload(
        internal val owners: Int,
        internal val modifiers: Int,
        internal val protocol: Protocol,
        internal val consumption: Consumption = Consumption.None,
        internal val acceptance: Acceptance = Acceptance.Many,
        internal val focused: Boolean = true,
    ) {
        PressNoModifiers(1, 0, Protocol.Press),
        PressFewEarly(1, 4, Protocol.Press, Consumption.Early),
        PressManyLate(1, 16, Protocol.Press, Consumption.Late),
        PressManyIgnored(1, 16, Protocol.Press),
        ReleaseNoModifiers(1, 0, Protocol.Release),
        ReleaseFewLate(1, 4, Protocol.Release, Consumption.Late),
        ReleaseManyEarly(1, 16, Protocol.Release, Consumption.Early),
        ReleaseManyIgnored(1, 16, Protocol.Release),
        CharacterNoModifiers(1, 0, Protocol.Character),
        CharacterFewEarly(1, 4, Protocol.Character, Consumption.Early),
        CharacterManyLate(1, 16, Protocol.Character, Consumption.Late),
        CharacterManyIgnored(1, 16, Protocol.Character),
        PreeditNoModifiers(1, 0, Protocol.Preedit),
        PreeditFewLate(1, 4, Protocol.Preedit, Consumption.Late),
        PreeditManyEarly(1, 16, Protocol.Preedit, Consumption.Early),
        PreeditManyIgnored(1, 16, Protocol.Preedit),
        NoFocusPress(1, 16, Protocol.Press, acceptance = Acceptance.None, focused = false),
        NoFocusCharacter(1, 16, Protocol.Character, acceptance = Acceptance.None, focused = false),
        TabSingle(1, 0, Protocol.Tab),
        ShiftTabSingle(1, 0, Protocol.ShiftTab),
        TabSparse128(128, 4, Protocol.Tab, acceptance = Acceptance.Few),
        ShiftTabSparse128(128, 4, Protocol.ShiftTab, acceptance = Acceptance.Few),
        TabMany128(128, 0, Protocol.Tab),
        ShiftTabMany128(128, 0, Protocol.ShiftTab),
        TabNone128(128, 0, Protocol.Tab, acceptance = Acceptance.None),
        ShiftTabNone128(128, 0, Protocol.ShiftTab, acceptance = Acceptance.None),
        TabSparse10000(10000, 0, Protocol.Tab, acceptance = Acceptance.Few),
        ShiftTabSparse10000(10000, 0, Protocol.ShiftTab, acceptance = Acceptance.Few),
        TabMany10000(10000, 0, Protocol.Tab),
        ShiftTabMany10000(10000, 0, Protocol.ShiftTab),
        TabNone10000(10000, 0, Protocol.Tab, acceptance = Acceptance.None),
        ShiftTabNone10000(10000, 0, Protocol.ShiftTab),
    }

    /**
     * Typed event selection; native input delivery and presentation are outside this CPU interval.
     */
    internal enum class Protocol { Press, Release, Character, Preedit, Tab, ShiftTab }

    /**
     * First capable member, component member, or complete ignored chain.
     */
    internal enum class Consumption { Early, Late, None }

    /**
     * Current target distribution independent of the directional anchor.
     */
    internal enum class Acceptance { None, Few, Many }

    private class Fixture(
        private val workload: Workload,
    ) : AutoCloseable {
        private val counters = Counters()
        private val constraints = Constraints.fixed(10, 10)
        private val anchor = if (workload.owners == 1) 0 else workload.owners / 2
        private val targets =
            when (workload.acceptance) {
                Acceptance.None -> emptySet()
                Acceptance.Few -> setOf(if (workload.owners == 1) 0 else 1, anchor, workload.owners - 1)
                Acceptance.Many -> ((if (workload.owners == 1) 0 else 1) until workload.owners).toSet()
            }
        private val nodes = ArrayList<InputNode>()
        private val session: RuntimeUiSession
        private val keyboard =
            when (workload.protocol) {
                Protocol.Release -> KeyboardEvent.Release(KeyCode.Enter, 7)
                Protocol.Tab -> KeyboardEvent.Press(KeyCode.Tab, 7)
                Protocol.ShiftTab -> KeyboardEvent.Press(KeyCode.Tab, 7, KeyboardModifiers(shift = true))
                Protocol.Press, Protocol.Character, Protocol.Preedit -> KeyboardEvent.Press(KeyCode.Enter, 7)
            }
        private val text =
            if (workload.protocol === Protocol.Preedit) {
                TextInputEvent.Preedit("compose", 3, listOf("com", "pose"), 1)
            } else {
                TextInputEvent.Character(0x1F642)
            }
        private val preparedFrame: Any

        init {
            val elements = List(workload.owners) { owner -> element(owner) }
            val root = InputElement(elements[0].node, elements.drop(1), elements[0].modifier)
            session = createRuntimeUiSession { root }
            session.attach()
            preparedFrame = session.frame(constraints)
            nodes.forEach { it.accepting = it.owner in targets }
            check(counters.focused == if (workload.focused) anchor else null)
        }

        fun dispatch(): InputResult =
            when (workload.protocol) {
                Protocol.Character, Protocol.Preedit -> session.dispatchTextInput(text)
                Protocol.Press, Protocol.Release, Protocol.Tab, Protocol.ShiftTab -> session.dispatchKeyboard(keyboard)
            }

        fun verifyWork() {
            val measures = counters.measures
            val layouts = counters.layouts
            counters.record = true
            repeat(8) {
                val current = counters.focused
                val order = (0 until workload.modifiers).reversed().toList() + workload.modifiers
                val delivered = if (current == null) emptyList() else order.map { Actor(current, it) }
                val expectedTrace =
                    when (workload.consumption) {
                        Consumption.Early -> delivered.take(1)
                        Consumption.Late, Consumption.None -> delivered
                    }
                val consumed = current != null && workload.consumption !== Consumption.None
                val traversal = workload.protocol === Protocol.Tab || workload.protocol === Protocol.ShiftTab
                val next = if (traversal && consumed.not()) expectedNext(current) else current
                val expectedResult = if (consumed || (traversal && next != null && next in targets)) InputResult.Consumed else InputResult.Ignored
                val token = session.textInputFocus
                counters.trace.clear()
                counters.transitions.clear()
                counters.callbacks = 0
                counters.acceptanceReads.clear()
                val traversalOrder = if (traversal && consumed.not()) expectedTraversal(current) else emptyList()
                val firstAccepting = traversalOrder.indexOfFirst { it in targets }
                val candidateReads = if (firstAccepting < 0) traversalOrder else traversalOrder.take(firstAccepting + 1)
                val expectedReads = candidateReads + if (next != current && next != null) listOf(next) else emptyList()

                check(dispatch() === expectedResult)
                check(counters.trace == expectedTrace && counters.callbacks == expectedTrace.size)
                check(counters.acceptanceReads == expectedReads)
                check(counters.focused == next)
                val expectedTransitions =
                    if (next == current) {
                        emptyList()
                    } else {
                        listOfNotNull(current?.let { Transition(it, false) }, next?.let { Transition(it, true) })
                    }
                check(counters.transitions == expectedTransitions)
                if (next == current) check(session.textInputFocus === token)
                check(session.frame(constraints) === preparedFrame)
                check(counters.measures == measures && counters.layouts == layouts)
            }
            counters.record = false
            counters.trace.clear()
            counters.transitions.clear()
            counters.acceptanceReads.clear()
        }

        override fun close() {
            session.close()
            check(counters.disposals == workload.owners * (workload.modifiers + 1))
            nodes.clear()
            counters.trace.clear()
            counters.transitions.clear()
            counters.acceptanceReads.clear()
        }

        private fun expectedNext(current: Int?): Int? = expectedTraversal(current).firstOrNull { it in targets } ?: current

        private fun expectedTraversal(current: Int?): List<Int> {
            val owners = (0 until workload.owners).toList()
            val reverse = workload.protocol === Protocol.ShiftTab
            return when {
                current == null && reverse -> owners.reversed()
                current == null -> owners
                reverse -> owners.take(current).reversed() + owners.drop(current).reversed()
                else -> owners.drop(current + 1) + owners.take(current + 1)
            }
        }

        private fun element(owner: Int): InputElement {
            val node = InputNode(owner, owner == anchor && workload.focused, workload, counters)
            nodes += node
            var modifier: Modifier = Modifier.Empty
            repeat(workload.modifiers) { index -> modifier = modifier.then(DispatchElement(owner, index, workload, counters)) }
            return InputElement(node, modifier = modifier)
        }
    }

    private class Counters {
        var focused: Int? = null
        var record = false
        var callbacks = 0
        var measures = 0
        var layouts = 0
        var disposals = 0
        val trace = ArrayList<Actor>()
        val transitions = ArrayList<Transition>()
        val acceptanceReads = ArrayList<Int>()

        fun deliver(
            owner: Int,
            member: Int,
            workload: Workload,
        ): InputResult {
            callbacks += 1
            if (record) trace += Actor(owner, member)
            return when (workload.consumption) {
                Consumption.None -> InputResult.Ignored
                Consumption.Early -> if (member == maxOf(0, workload.modifiers - 1)) InputResult.Consumed else InputResult.Ignored
                Consumption.Late -> if (member == workload.modifiers) InputResult.Consumed else InputResult.Ignored
            }
        }
    }

    private class InputNode(
        val owner: Int,
        private val initial: Boolean,
        private val workload: Workload,
        private val counters: Counters,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        FocusTargetNode,
        KeyboardInputNode,
        TextInputNode,
        LifecycleNode {
        var accepting = initial

        override val acceptsFocus: Boolean
            get() {
                if (counters.record) counters.acceptanceReads += owner
                return accepting
            }

        override val requestsInitialFocus: Boolean
            get() = initial

        override val requiresTextInput: Boolean
            get() = true

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            counters.measures += 1
            repeat(scope.childCount) { scope.measureChild(it, constraints) }
            return constraints.constrain(IntSize(10, 10))
        }

        override fun layout(scope: LayoutScope) {
            counters.layouts += 1
            repeat(scope.childCount) { scope.placeChild(it, IntOffset.Zero) }
        }

        override fun onFocusChanged(focused: Boolean) {
            if (focused) counters.focused = owner
            if (counters.record) counters.transitions += Transition(owner, focused)
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult = counters.deliver(owner, workload.modifiers, workload)

        override fun onTextInput(event: TextInputEvent): InputResult = counters.deliver(owner, workload.modifiers, workload)

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            counters.disposals += 1
        }
    }

    private class DispatchNode(
        private val element: DispatchElement,
    ) : ModifierNode(),
        KeyboardInputNode,
        TextInputNode,
        LifecycleNode {
        override fun onKeyboardEvent(event: KeyboardEvent): InputResult = element.counters.deliver(element.owner, element.member, element.workload)

        override fun onTextInput(event: TextInputEvent): InputResult = element.counters.deliver(element.owner, element.member, element.workload)

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            element.counters.disposals += 1
        }
    }

    private class InputElement(
        val node: InputNode,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
    ) : Element(ElementIdentity.Positional, inputType, children, modifier)

    private data class DispatchElement(
        val owner: Int,
        val member: Int,
        val workload: Workload,
        val counters: Counters,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = dispatchType
    }

    private data class Actor(
        val owner: Int,
        val member: Int,
    )

    private data class Transition(
        val owner: Int,
        val focused: Boolean,
    )

    /**
     * Generated-metadata registration and the independent dispatch oracle, invoked before any collection.
     */
    public companion object {
        private val inputType =
            ElementType(
                elementClass = InputElement::class,
                nodeClass = InputNode::class,
                validateLocal = {},
                createNode = InputElement::node,
                updateNode = { _, _, _ -> DirtyMask.None },
            )
        private val dispatchType =
            ModifierNodeType(
                elementClass = DispatchElement::class,
                nodeClass = DispatchNode::class,
                validateLocal = {},
                createNode = ::DispatchNode,
                updateNode = { _, _, _ -> DirtyMask.None },
            )

        /**
         * Verifies every frozen case with traces enabled outside timing and exact disposal counts.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(FocusedInputBenchmark::class.java), setOf("avgt")).size == Workload.entries.size)
            Workload.entries.forEach { workload -> Fixture(workload).use { it.verifyWork() } }
        }
    }
}
