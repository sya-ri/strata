package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DynamicChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Independent capable and capability-free primitives with typed callback observations on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class TraversalTestFixture {
    /**
     * Current nodes keyed only by the test declaration identity.
     */
    val nodes: MutableMap<Int, ProbeNode> = LinkedHashMap()

    /**
     * Callback order for the current test interval.
     */
    val events: MutableList<Event> = ArrayList()

    /**
     * Optional actions, including invalidation and failures, invoked inside a selected callback.
     */
    val callbacks: MutableMap<Event, () -> Unit> = HashMap()

    /**
     * Creates one keyed immutable primitive with independently selected capability membership.
     */
    fun element(
        id: Int,
        kind: Kind = Kind.Plain,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
        width: Int = 4,
        accepting: Boolean = true,
        initial: Boolean = false,
        unplacedChild: Int? = null,
    ): ProbeElement = ProbeElement(this, id, kind, children, modifier, width, accepting, initial, unplacedChild)

    /**
     * Creates an active modifier whose immutable kind either supplies focus and semantics or supplies neither.
     */
    fun modifier(
        id: Int,
        participant: Boolean = true,
        initial: Boolean = false,
    ): Modifier = Modifier.Empty.then(ProbeModifierElement(this, id, participant, initial))

    /**
     * Returns a detached retained root for direct summary and ownership tests.
     */
    fun reconcile(description: Element): Pair<RetainedNode, LifecycleManager> {
        val tracker = DirtyTracker()
        val lifecycle = LifecycleManager(NodeOwnershipRegistry(), OwnerGuard(), tracker) {}
        val reconciler = Reconciler(lifecycle, tracker)
        val root = reconciler.reconcileRoot(null, description)
        reconciler.markInstalled(root)
        lifecycle.attachPending(root)
        return root to lifecycle
    }

    /**
     * Fixed immutable capability membership; changing it selects a different element token.
     */
    enum class Kind {
        Plain,
        Dynamic,
        Focus,
        Semantics,
        Participant,
    }

    /**
     * Callback categories distinguish ordering and failure injection without text discriminators.
     */
    enum class Phase {
        Attach,
        Detach,
        Dispose,
        Dynamic,
        Measure,
        Layout,
        Semantics,
        FocusGained,
        FocusLost,
    }

    /**
     * One callback on one component or modifier.
     */
    data class Event(val phase: Phase, val id: Int)

    private fun record(
        phase: Phase,
        id: Int,
    ) {
        val event = Event(phase, id)
        events.add(event)
        callbacks[event]?.invoke()
    }

    /**
     * One immutable declaration; live test actions belong to its fixture, not to capability summaries.
     */
    class ProbeElement(
        val fixture: TraversalTestFixture,
        val id: Int,
        kind: Kind,
        children: List<Element>,
        modifier: Modifier,
        val width: Int,
        val accepting: Boolean,
        val initial: Boolean,
        val unplacedChild: Int?,
    ) : Element(ElementIdentity.Keyed(ElementKey(id)), types.getValue(kind), children, modifier) {
        /**
         * Stable referential tokens distinguish immutable capability membership.
         */
        companion object {
            private val types =
                Kind.entries.associateWith { kind ->
                    ElementType(
                        ProbeElement::class,
                        ProbeNode::class,
                        {},
                        { element ->
                            val node =
                                when (kind) {
                                    Kind.Plain -> ProbeNode(element)
                                    Kind.Dynamic -> DynamicProbe(element)
                                    Kind.Focus -> FocusProbe(element)
                                    Kind.Semantics -> SemanticsProbe(element)
                                    Kind.Participant -> ParticipantProbe(element)
                                }
                            element.fixture.nodes[element.id] = node
                            node
                        },
                        { _, current, node ->
                            node.description = current
                            DirtyMask.All
                        },
                    )
                }
        }
    }

    /**
     * Shared geometry and lifecycle behavior; it deliberately supplies no focus or semantics capability.
     */
    open class ProbeNode(var description: ProbeElement) : Node(), MeasureNode, LayoutNode, LifecycleNode {
        /**
         * Publishes phase work through the real owner-confined runtime binding.
         */
        fun dirty(phase: DirtyPhase = DirtyPhase.Measure) = invalidate(DirtyMask.of(phase))

        override fun attach() = record(Phase.Attach)

        override fun detach() = record(Phase.Detach)

        override fun dispose() = record(Phase.Dispose)

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            record(Phase.Measure)
            var width = description.width
            var height = 4
            for (index in 0 until scope.childCount) {
                val child = scope.measureChild(index, Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
                width = maxOf(width, child.width)
                height = maxOf(height, child.height)
            }
            return constraints.constrain(IntSize(width, height))
        }

        override fun layout(scope: LayoutScope) {
            record(Phase.Layout)
            for (index in 0 until scope.childCount) {
                if (index != description.unplacedChild) scope.placeChild(index, IntOffset.Zero)
            }
        }

        /**
         * Records a callback before invoking its arbitrary test action.
         */
        protected fun record(phase: Phase) = description.fixture.record(phase, description.id)
    }

    /**
     * Live dynamic declarations let parent callbacks insert and remove descendants before the same traversal reaches them.
     */
    class DynamicProbe(description: ProbeElement) : ProbeNode(description), DynamicChildrenNode {
        /**
         * Current dynamic children, never copied into a traversal summary.
         */
        var descriptions: List<Element> = emptyList()

        override fun dynamicChildren(): List<Element> {
            record(Phase.Dynamic)
            return descriptions
        }
    }

    /**
     * Mutable acceptance remains a live property of the current description.
     */
    private open class FocusProbe(description: ProbeElement) : ProbeNode(description), FocusTargetNode {
        override val acceptsFocus: Boolean get() = description.accepting
        override val requestsInitialFocus: Boolean get() = description.initial

        override fun onFocusChanged(focused: Boolean) = record(if (focused) Phase.FocusGained else Phase.FocusLost)
    }

    /**
     * Emits one unresolved payload through the actual guarded semantics scope.
     */
    private class SemanticsProbe(description: ProbeElement) : ProbeNode(description), SemanticsNode {
        override fun semantics(scope: SemanticsScope) {
            record(Phase.Semantics)
            scope.emit(Semantics(label = UiText.Literal(description.id.toString())))
        }
    }

    /**
     * Supplies both independent participant capabilities for dense-tree controls.
     */
    private class ParticipantProbe(description: ProbeElement) : FocusProbe(description), SemanticsNode {
        override fun semantics(scope: SemanticsScope) {
            record(Phase.Semantics)
            scope.emit(Semantics(label = UiText.Literal(description.id.toString())))
        }
    }

    private class ProbeModifierElement(
        val fixture: TraversalTestFixture,
        val id: Int,
        participant: Boolean,
        val initial: Boolean,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> = if (participant) ParticipantType else PlainType

        companion object {
            private val PlainType = ModifierNodeType(ProbeModifierElement::class, ProbeModifier::class, {}, { ProbeModifier(it) }, { _, _, _ -> DirtyMask.None })
            private val ParticipantType = ModifierNodeType(ProbeModifierElement::class, ParticipantModifier::class, {}, { ParticipantModifier(it) }, { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Ordinary modifier ownership, including detach/dispose ordering on replacement.
     */
    private open class ProbeModifier(protected val description: ProbeModifierElement) : ModifierNode(), LifecycleNode {
        override fun attach() = record(Phase.Attach)
        override fun detach() = record(Phase.Detach)
        override fun dispose() = record(Phase.Dispose)

        /**
         * Records this modifier under its own callback identity.
         */
        protected fun record(phase: Phase) = description.fixture.record(phase, description.id)
    }

    /**
     * Active modifier ancestry contributes capabilities to its logical owner.
     */
    private class ParticipantModifier(description: ProbeModifierElement) : ProbeModifier(description), FocusTargetNode, SemanticsNode {
        override val acceptsFocus: Boolean get() = true
        override val requestsInitialFocus: Boolean get() = description.initial

        override fun onFocusChanged(focused: Boolean) = record(if (focused) Phase.FocusGained else Phase.FocusLost)

        override fun semantics(scope: SemanticsScope) {
            record(Phase.Semantics)
            scope.emit(Semantics(label = UiText.Literal(description.id.toString())))
        }
    }
}
