@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.fixture

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
import dev.s7a.strata.node.DynamicChildrenNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Independent SPI callback counters and lifecycle observations used unchanged by both runtime archives.
 * All state belongs to one invocation owner; no clock, runtime-variant selection or shared scratch exists.
 */
@Suppress("TooManyFunctions")
public class EmptyChildProbe {
    private val calls = IntArray(Stage.entries.size)
    /**
     * Current test-owned node handles; setup and terminal checks may inspect them.
     */
    public val nodes: MutableList<ProbeNode> = mutableListOf()
    /**
     * Ordered lifecycle attempts, bounded by the single invocation's tree.
     */
    public val events: MutableList<Event> = mutableListOf()
    /**
     * Optional exact failure injected at one typed callback boundary.
     */
    public var failure: Failure? = null
    /**
     * Later cleanup failures used to verify suppression order independently.
     */
    public var cleanupFailures: List<Failure> = emptyList()
    /**
     * Optional update hook for cutoff and localized-work controls.
     */
    public var onUpdate: (() -> Unit)? = null
    /**
     * Complete immutable output of the dynamic test primitive.
     */
    public var dynamicOutput: List<Element> = emptyList()

    /**
     * Resets interval observations after setup without changing retained nodes.
     */
    public fun checkpoint() {
        calls.fill(0)
        events.clear()
    }

    /**
     * Returns actual callback attempts in the current interval.
     */
    public fun count(stage: Stage): Int = calls[stage.ordinal]

    /**
     * Records one callback and injects the original failure before its effect, when selected.
     */
    public fun record(stage: Stage, id: Int) {
        calls[stage.ordinal] += 1
        when (stage) {
            Stage.Attach, Stage.Detach, Stage.Dispose -> events.add(Event(stage, id))
            else -> Unit
        }
        failure?.let { selected -> if (selected.stage == stage && selected.id == id) throw selected.cause }
        cleanupFailures.forEach { selected -> if (selected.stage == stage && selected.id == id) throw selected.cause }
    }

    /**
     * Creates a fresh compatible description with stable typed sibling identity.
     */
    @Suppress("LongParameterList")
    public fun element(
        id: Int,
        payload: Int = 0,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
        kind: Kind = Kind.Ordinary,
        keyed: Boolean = true,
        mask: DirtyMask = DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics),
        role: Role = Role.Target,
    ): ProbeElement = ProbeElement(this, id, payload, children, modifier, kind, keyed, mask, role)

    /**
     * Creates one fresh pass-through modifier with independent validate/update hooks.
     */
    public fun modifier(id: Int, payload: Int = 0): Modifier = Modifier.Empty.then(ProbeModifier(this, id, payload))

    /**
     * Callback boundaries; values are observations, never runtime branch predictions.
     */
    public enum class Stage {
        Validate, Create, Update, Attach, Detach, Dispose, Measure, Layout, Paint, Semantics,
        ModifierValidate, ModifierCreate, ModifierUpdate, Dynamic,
        /**
         * Compatible ordinary update whose previous and incoming child lists are both empty.
         */
        EligibleUpdate,
        /**
         * Compatible ordinary update with at least one nonempty child list.
         */
        NonemptyUpdate,
        /**
         * Eligible callback at the workload's empty target component.
         */
        TargetEligibleUpdate,
        /**
         * Ordinary matching required at a nonempty transition target parent.
         */
        TargetMissUpdate,
        /**
         * Independently eligible fresh descendant under a nonempty transition target.
         */
        DescendantEligibleUpdate,
        /**
         * Descendant callback requiring ordinary nonempty matching.
         */
        DescendantMissUpdate,
        /**
         * Ordinary matching at the fixed surrounding container.
         */
        SurroundingMissUpdate,
    }

    /**
     * Compatible token selection for explicit replacement and dynamic controls.
     */
    public enum class Kind { Ordinary, Alternate, Dynamic }

    /**
     * Fixture roles distinguish target-parent work from independently eligible descendants and surrounding containers.
     */
    public enum class Role { Target, Descendant, Surrounding }

    /**
     * Detached ordered lifecycle evidence.
     */
    public data class Event(public val stage: Stage, public val id: Int)

    /**
     * Injection configuration owned by a single control.
     */
    public data class Failure(public val stage: Stage, public val id: Int, public val cause: Throwable)

    /**
     * Immutable SPI declaration; construction and snapshot copying remain inside sampled updates.
     */
    @Suppress("LongParameterList")
    public class ProbeElement(
        /**
         * Owner of independent work counters.
         */
        public val probe: EmptyChildProbe,
        /**
         * Stable typed key payload.
         */
        public val id: Int,
        /**
         * Size, color and semantics value.
         */
        public val payload: Int,
        children: List<Element>,
        modifier: Modifier,
        kind: Kind,
        keyed: Boolean,
        /**
         * Exact diff mask tested by phase-specific controls.
         */
        public val mask: DirtyMask,
        /**
         * Independent workload role; never selected by the runtime variant.
         */
        public val role: Role,
    ) : Element(
        if (keyed) ElementIdentity.Keyed(ElementKey(id)) else ElementIdentity.Positional,
        when (kind) {
            Kind.Ordinary -> ordinaryType
            Kind.Alternate -> alternateType
            Kind.Dynamic -> dynamicType
        },
        children,
        modifier,
    )

    /**
     * Test-owned retained node; callbacks expose real phase work and terminal retirement.
     */
    public open class ProbeNode(
        /**
         * Invocation-owned independent callback observations.
         */
        protected val probe: EmptyChildProbe,
        /**
         * Stable declaration key payload.
         */
        public val id: Int,
        /**
         * Current presentation value, updated only by the SPI hook.
         */
        public var payload: Int,
    ) : Node(), MeasureNode, LayoutNode, PaintNode, SemanticsNode, LifecycleNode {
        private var measuredPayload = payload
        private var paintedPayload = payload
        private var semanticsPayload = payload
        /**
         * Last direct-child count seen through the real layout scope.
         */
        public var childCount: Int = 0
        /**
         * Whether terminal disposal was attempted.
         */
        public var disposed: Boolean = false

        /**
         * Applies only the presentation fields selected by the declaration's exact diff mask.
         */
        public fun updatePayload(value: Int, mask: DirtyMask) {
            payload = value
            if (DirtyPhase.Measure in mask) measuredPayload = value
            if (DirtyPhase.Paint in mask) paintedPayload = value
            if (DirtyPhase.Semantics in mask) semanticsPayload = value
        }

        override fun attach() { probe.record(Stage.Attach, id) }
        override fun detach() { probe.record(Stage.Detach, id) }
        override fun dispose() {
            disposed = true
            probe.record(Stage.Dispose, id)
        }

        override fun measure(scope: MeasureScope, constraints: Constraints): IntSize {
            probe.record(Stage.Measure, id)
            childCount = scope.childCount
            var width = 0
            repeat(scope.childCount) { width += scope.measureChild(it, Constraints()).width }
            return constraints.constrain(IntSize(if (scope.childCount == 0) measuredPayload + 1 else width, 1))
        }

        override fun layout(scope: LayoutScope) {
            probe.record(Stage.Layout, id)
            var x = 0
            repeat(scope.childCount) {
                scope.placeChild(it, IntOffset(x, 0))
                x += scope.measuredChildSize(it).width
            }
        }

        override fun paint(scope: PaintScope) {
            probe.record(Stage.Paint, id)
            scope.fillRectangle(IntRect(0, 0, scope.size.width, 1), ArgbColor(0xFF000000.toInt() or paintedPayload))
        }

        override fun semantics(scope: SemanticsScope) {
            probe.record(Stage.Semantics, id)
            scope.emit(Semantics(label = UiText.literal(id.toString()), value = UiText.literal(semanticsPayload.toString())))
        }

        /**
         * Proves runtime callbacks were retired without rebinding the node.
         */
        public fun invalidateForControl() { invalidate(DirtyMask.All) }
    }

    /**
     * Dynamic SPI primitive exercising the real committed description-list path.
     */
    private class DynamicNode(probe: EmptyChildProbe, id: Int, payload: Int) : ProbeNode(probe, id, payload), DynamicChildrenNode {
        override fun dynamicChildren(): List<Element> {
            probe.record(Stage.Dynamic, id)
            return probe.dynamicOutput
        }
    }

    /**
     * Immutable active modifier used to observe preservation before child matching.
     */
    public data class ProbeModifier(
        public val probe: EmptyChildProbe,
        public val id: Int,
        public val payload: Int,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = modifierType
    }

    /**
     * Pass-through node with an independent update callback and ordinary virtual-child ancestry.
     */
    private class ProbeModifierNode : ModifierNode()

    /**
     * Singleton tokens make fresh descriptions compatible across all runtime variants.
     */
    public companion object {
        private fun type(dynamic: Boolean): ElementType<ProbeElement, ProbeNode> =
            ElementType(
                ProbeElement::class,
                ProbeNode::class,
                { it.probe.record(Stage.Validate, it.id) },
                {
                    it.probe.record(Stage.Create, it.id)
                    val node = if (dynamic) DynamicNode(it.probe, it.id, it.payload) else ProbeNode(it.probe, it.id, it.payload)
                    it.probe.nodes.add(node)
                    node
                },
                { previous, current, node ->
                    current.probe.record(Stage.Update, current.id)
                    if ((node is DynamicChildrenNode).not()) {
                        val eligible = previous.children.isEmpty() && current.children.isEmpty()
                        current.probe.record(
                            if (eligible) Stage.EligibleUpdate else Stage.NonemptyUpdate,
                            current.id,
                        )
                        val roleStage =
                            when (current.role) {
                                Role.Target -> if (eligible) Stage.TargetEligibleUpdate else Stage.TargetMissUpdate
                                Role.Descendant -> if (eligible) Stage.DescendantEligibleUpdate else Stage.DescendantMissUpdate
                                Role.Surrounding -> Stage.SurroundingMissUpdate
                            }
                        current.probe.record(roleStage, current.id)
                    }
                    current.probe.onUpdate?.invoke()
                    val mask = if (previous.payload == current.payload) DirtyMask.None else current.mask
                    node.updatePayload(current.payload, mask)
                    mask
                },
            )

        private val ordinaryType = type(false)
        private val alternateType = type(false)
        private val dynamicType = type(true)
        private val modifierType =
            ModifierNodeType(
                ProbeModifier::class,
                ProbeModifierNode::class,
                { it.probe.record(Stage.ModifierValidate, it.id) },
                { it.probe.record(Stage.ModifierCreate, it.id); ProbeModifierNode() },
                { _, current, _ -> current.probe.record(Stage.ModifierUpdate, current.id); DirtyMask.None },
            )
    }
}
