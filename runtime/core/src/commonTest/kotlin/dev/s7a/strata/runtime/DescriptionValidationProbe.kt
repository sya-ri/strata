package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
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
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * Independent callback/key/lifecycle trace and externally shaped primitives shared by JVM and JavaScript controls.
 */
internal class DescriptionValidationProbe {
    /**
     * Ordered observations; test assertions never decode string discriminators.
     */
    val trace = ArrayList<Event>()

    /**
     * Fresh retained node identities returned to the tree.
     */
    val nodes = ArrayList<ProbeNode>()

    /**
     * Constructs a description whose local validation may execute or throw an application callback.
     */
    fun element(
        id: Int,
        children: List<Element> = emptyList(),
        key: Key? = null,
        modifier: Modifier = Modifier.Empty,
        validate: () -> Unit = {},
        dispose: () -> Unit = {},
    ): ProbeElement = ProbeElement(this, id, children, key, modifier, validate, dispose)

    /**
     * Creates a stable caller key with explicit equality/hash behavior and optional exact failures.
     */
    fun key(
        id: Int,
        value: Int = id,
        hash: Int = 7,
        hashFailure: Throwable? = null,
        equalityFailure: Throwable? = null,
    ): Key = Key(this, id, value, hash, hashFailure, equalityFailure)

    /**
     * Creates one active modifier description with an independently observed validation hook.
     */
    fun modifier(
        id: Int,
        validate: () -> Unit = {},
        dispose: () -> Unit = {},
    ): ProbeModifier = ProbeModifier(this, id, validate, dispose)

    /**
     * Typed independent observations of application hooks and ownership.
     */
    sealed interface Event {
        /**
         * Local description validation.
         */
        data class Local(val id: Int) : Event

        /**
         * Immutable modifier validation.
         */
        data class ModifierValidation(val id: Int) : Event

        /**
         * Caller key hash invocation.
         */
        data class Hash(val id: Int) : Event

        /**
         * Caller key equality invocation, preserving receiver direction.
         */
        data class Equality(val receiver: Int, val argument: Int?) : Event

        /**
         * Retained ownership callback.
         */
        data class Ownership(val phase: Phase, val id: Int) : Event
    }

    /**
     * Application lifecycle and diff hooks observed separately from validation.
     */
    enum class Phase {
        Create,
        Update,
        Attach,
        Detach,
        Dispose,
        ModifierCreate,
        ModifierUpdate,
        ModifierAttach,
        ModifierDetach,
        ModifierDispose,
    }

    /**
     * A callback-observable key; wrapped values remain stable while validation is running.
     */
    class Key(
        private val probe: DescriptionValidationProbe,
        private val id: Int,
        private val value: Int,
        private val hash: Int,
        private val hashFailure: Throwable?,
        private val equalityFailure: Throwable?,
    ) {
        override fun hashCode(): Int {
            probe.trace.add(Event.Hash(id))
            hashFailure?.let { throw it }
            return hash
        }

        override fun equals(other: Any?): Boolean {
            probe.trace.add(Event.Equality(id, (other as? Key)?.id))
            equalityFailure?.let { throw it }
            return other is Key && value == other.value
        }

        override fun toString(): String = "key-$id"
    }

    /**
     * Detached immutable test description; callbacks belong to this test's probe.
     */
    class ProbeElement(
        val probe: DescriptionValidationProbe,
        val id: Int,
        children: List<Element>,
        key: Key?,
        modifier: Modifier,
        val validate: () -> Unit,
        val dispose: () -> Unit,
    ) : Element(key?.let { ElementIdentity.Keyed(ElementKey(it)) } ?: ElementIdentity.Positional, TYPE, children, modifier) {
        /**
         * Stable externally shaped token preserves validation-before-create observations.
         */
        companion object {
            private val TYPE =
                ElementType(
                    elementClass = ProbeElement::class,
                    nodeClass = ProbeNode::class,
                    validateLocal = {
                        it.probe.trace.add(Event.Local(it.id))
                        it.validate()
                    },
                    createNode = {
                        it.probe.trace.add(Event.Ownership(Phase.Create, it.id))
                        ProbeNode(it.probe, it.id, it.dispose).also(it.probe.nodes::add)
                    },
                    updateNode = { _, current, node ->
                        current.probe.trace.add(Event.Ownership(Phase.Update, current.id))
                        node.id = current.id
                        node.onDispose = current.dispose
                        DirtyMask.of(DirtyPhase.Semantics)
                    },
                )
        }
    }

    /**
     * Fully placed primitive with deterministic rectangles, semantics, pointer results and retained editing data.
     */
    class ProbeNode(
        private val probe: DescriptionValidationProbe,
        var id: Int,
        var onDispose: () -> Unit,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        SemanticsNode,
        PointerInputNode,
        TextInputNode,
        FocusTargetNode,
        LifecycleNode {
        /**
         * Caller editing state must survive compatible updates and rejected descriptions.
         */
        var editing = 0

        /**
         * Actual logical focus ownership, preserved across rejected declarations.
         */
        var focused = false
            private set

        override val acceptsFocus: Boolean get() = true
        override val requestsInitialFocus: Boolean get() = false
        override val requiresTextInput: Boolean get() = true

        override fun onFocusChanged(focused: Boolean) {
            this.focused = focused
        }

        override fun onTextInput(event: TextInputEvent): InputResult {
            if (event is TextInputEvent.Character) editing += event.codePoint
            return InputResult.Consumed
        }

        override fun measure(scope: MeasureScope, constraints: Constraints): IntSize {
            for (child in 0 until scope.childCount) scope.measureChild(child, Constraints(maxWidth = 2, maxHeight = 2))
            return constraints.constrain(IntSize(2, 2))
        }

        override fun layout(scope: LayoutScope) {
            for (child in 0 until scope.childCount) scope.placeChild(child, IntOffset.Zero)
        }

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF112233.toInt()))
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = UiText.Literal("node-$id")))
        }

        override fun onPointerEvent(event: PointerEvent, localPosition: IntOffset): InputResult = InputResult.Consumed

        override fun attach() {
            probe.trace.add(Event.Ownership(Phase.Attach, id))
        }

        override fun detach() {
            probe.trace.add(Event.Ownership(Phase.Detach, id))
        }

        override fun dispose() {
            probe.trace.add(Event.Ownership(Phase.Dispose, id))
            onDispose()
        }
    }

    /**
     * Active pass-through modifier with observable local validation and lifecycle.
     */
    class ProbeModifier(
        val probe: DescriptionValidationProbe,
        val id: Int,
        val validate: () -> Unit,
        val dispose: () -> Unit,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = TYPE

        /**
         * Stable modifier kind shared by all descriptions; distinct replacements keep the component identity.
         */
        companion object {
            private val TYPE =
                ModifierNodeType(
                    elementClass = ProbeModifier::class,
                    nodeClass = ProbeModifierNode::class,
                    validateLocal = {
                        it.probe.trace.add(Event.ModifierValidation(it.id))
                        it.validate()
                    },
                    createNode = {
                        it.probe.trace.add(Event.Ownership(Phase.ModifierCreate, it.id))
                        ProbeModifierNode(it.probe, it.id, it.dispose)
                    },
                    updateNode = { _, current, node ->
                        current.probe.trace.add(Event.Ownership(Phase.ModifierUpdate, current.id))
                        node.id = current.id
                        node.onDispose = current.dispose
                        DirtyMask.None
                    },
                )
        }
    }

    /**
     * Retained active modifier owner, independent of component hooks.
     */
    class ProbeModifierNode(
        private val probe: DescriptionValidationProbe,
        var id: Int,
        var onDispose: () -> Unit,
    ) : ModifierNode(), LifecycleNode {
        override fun attach() {
            probe.trace.add(Event.Ownership(Phase.ModifierAttach, id))
        }

        override fun detach() {
            probe.trace.add(Event.Ownership(Phase.ModifierDetach, id))
        }

        override fun dispose() {
            probe.trace.add(Event.Ownership(Phase.ModifierDispose, id))
            onDispose()
        }
    }
}
