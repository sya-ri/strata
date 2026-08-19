package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * Runtime-owned fixture for deterministic active modifier tests.
 *
 * The fixture exposes stable typed modifier tokens, lifecycle observations, failure injection, and node identity.
 */
internal class ModifierTestFixture {
    /**
     * Builds a typed modifier description for runtime contract tests.
     *
     * @param probe the lifecycle and identity observation owner.
     * @param id the fixture node identity.
     * @param kind the stable modifier token choice.
     * @param behavior the effective child behavior.
     * @param updateMask the phases returned by the update bridge.
     * @param invalid whether validation should reject the description.
     * @param createFailure the failure raised while creating the node.
     * @param updateFailure the failure raised while updating a retained node.
     * @param sharedNode an optional deliberately aliased node.
     * @return an immutable typed modifier description.
     */
    internal fun modifier(
        probe: Probe,
        id: Int,
        kind: Kind,
        behavior: Behavior = Behavior.PassThrough,
        updateMask: DirtyMask = DirtyMask.None,
        invalid: Boolean = false,
        createFailure: Throwable? = null,
        updateFailure: Throwable? = null,
        sharedNode: TestModifierNode? = null,
    ): TestModifierElement =
        TestModifierElement(
            probe = probe,
            id = id,
            kind = kind,
            behavior = behavior,
            updateMask = updateMask,
            invalid = invalid,
            createFailure = createFailure,
            updateFailure = updateFailure,
            sharedNode = sharedNode,
        )

    /**
     * Builds an immutable modifier chain in declaration order.
     *
     * @param elements the modifier descriptions from outermost to innermost.
     * @return the resulting immutable chain.
     */
    internal fun chain(vararg elements: TestModifierElement): Modifier = elements.fold(Modifier.Empty) { current, element -> current.then(element) }

    /**
     * Builds the expected full-tree lifecycle order for the focused two-level fixture.
     *
     * @param rootId the logical root component identity.
     * @param childId the logical child component identity.
     * @param outerModifierId the outer root modifier identity.
     * @param innerModifierId the inner root modifier identity.
     * @param childModifierId the child modifier identity.
     * @return typed lifecycle observations in initial-attach and final-cleanup order.
     */
    internal fun expectedTreeLifecycle(
        rootId: TestProbe.ProbeId,
        childId: TestProbe.ProbeId,
        outerModifierId: Int,
        innerModifierId: Int,
        childModifierId: Int,
    ): List<LifecycleObservation> =
        listOf(
            LifecycleObservation.Modifier(Event.Attach(outerModifierId)),
            LifecycleObservation.Modifier(Event.Attach(innerModifierId)),
            LifecycleObservation.Component(TestProbe.Event.Attach(rootId)),
            LifecycleObservation.Modifier(Event.Attach(childModifierId)),
            LifecycleObservation.Component(TestProbe.Event.Attach(childId)),
            LifecycleObservation.Component(TestProbe.Event.Detach(childId)),
            LifecycleObservation.Component(TestProbe.Event.Dispose(childId)),
            LifecycleObservation.Modifier(Event.Detach(childModifierId)),
            LifecycleObservation.Modifier(Event.Dispose(childModifierId)),
            LifecycleObservation.Component(TestProbe.Event.Detach(rootId)),
            LifecycleObservation.Component(TestProbe.Event.Dispose(rootId)),
            LifecycleObservation.Modifier(Event.Detach(innerModifierId)),
            LifecycleObservation.Modifier(Event.Dispose(innerModifierId)),
            LifecycleObservation.Modifier(Event.Detach(outerModifierId)),
            LifecycleObservation.Modifier(Event.Dispose(outerModifierId)),
        )

    /**
     * Stable referential modifier token choices.
     */
    internal enum class Kind {
        /**
         * First modifier token.
         */
        First,

        /**
         * Second modifier token.
         */
        Second,

        /**
         * Third modifier token.
         */
        Third,
    }

    /**
     * Modifier measurement behavior used to exclude a logical component from effective descendants.
     */
    internal enum class Behavior {
        /**
         * Inherits the one-child pass-through behavior.
         */
        PassThrough,

        /**
         * Measures no virtual child and places no virtual child.
         */
        ExcludeChild,
    }

    /**
     * Typed lifecycle observation emitted by fixture modifier nodes.
     */
    internal sealed interface Event {
        /**
         * A modifier attached.
         */
        data class Attach(
            val id: Int,
        ) : Event

        /**
         * A modifier detached.
         */
        data class Detach(
            val id: Int,
        ) : Event

        /**
         * A modifier disposed.
         */
        data class Dispose(
            val id: Int,
        ) : Event
    }

    /**
     * Typed lifecycle observation shared by modifier and component ordering tests.
     */
    internal sealed interface LifecycleObservation {
        /**
         * One modifier lifecycle event.
         *
         * @property event the observed modifier event.
         */
        data class Modifier(
            val event: Event,
        ) : LifecycleObservation

        /**
         * One logical component lifecycle event.
         *
         * @property event the observed component event.
         */
        data class Component(
            val event: TestProbe.Event,
        ) : LifecycleObservation
    }

    /**
     * Explicit observations and failure controls shared by fixture descriptions and nodes.
     */
    internal class Probe {
        /**
         * Lifecycle events in callback order.
         */
        val events: MutableList<Event> = ArrayList()

        /**
         * Nodes created for each description id.
         */
        val nodes: MutableMap<Int, TestModifierNode> = HashMap()

        /**
         * Typed modifier update observations in callback order.
         */
        val updates: MutableList<Update> = ArrayList()

        /**
         * Measurement callback counts grouped by modifier identity.
         */
        val measureCalls: MutableMap<Int, Int> = HashMap()

        /**
         * Paint callback counts grouped by modifier identity.
         */
        val paintCalls: MutableMap<Int, Int> = HashMap()

        /**
         * Lifecycle failures injected by callback kind.
         */
        var attachFailure: Throwable? = null

        /**
         * Detach failure injected by callback kind.
         */
        var detachFailure: Throwable? = null

        /**
         * Dispose failure injected by callback kind.
         */
        var disposeFailure: Throwable? = null

        /**
         * Optional callback invoked while a modifier is disposed.
         */
        var onDispose: (() -> Unit)? = null

        /**
         * Optional observer for the complete modifier lifecycle stream.
         */
        var onEvent: ((Event) -> Unit)? = null

        /**
         * Per-node attach failures used to verify cleanup ordering.
         */
        val attachFailures: MutableMap<Int, Throwable> = HashMap()

        /**
         * Per-node detach failures used to verify cleanup ordering.
         */
        val detachFailures: MutableMap<Int, Throwable> = HashMap()

        /**
         * Per-node dispose failures used to verify cleanup ordering.
         */
        val disposeFailures: MutableMap<Int, Throwable> = HashMap()
    }

    /**
     * Records one typed modifier description update.
     *
     * @property previousId the identity carried by the previous description.
     * @property currentId the identity carried by the incoming description.
     */
    internal data class Update(
        val previousId: Int,
        val currentId: Int,
    )

    /**
     * Immutable external-style modifier description with three stable typed tokens.
     */
    internal data class TestModifierElement(
        val probe: Probe,
        val id: Int,
        val kind: Kind,
        val behavior: Behavior = Behavior.PassThrough,
        val updateMask: DirtyMask = DirtyMask.None,
        val invalid: Boolean = false,
        val createFailure: Throwable? = null,
        val updateFailure: Throwable? = null,
        val sharedNode: TestModifierNode? = null,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() =
                when (kind) {
                    Kind.First -> FIRST
                    Kind.Second -> SECOND
                    Kind.Third -> THIRD
                }

        /**
         * Stable typed token for the first modifier kind.
         */
        companion object {
            val FIRST: ModifierNodeType<TestModifierElement, TestModifierNode> = createType(Kind.First)

            /**
             * Stable typed token for the second modifier kind.
             */
            val SECOND: ModifierNodeType<TestModifierElement, TestModifierNode> = createType(Kind.Second)

            /**
             * Stable typed token for the third modifier kind.
             */
            val THIRD: ModifierNodeType<TestModifierElement, TestModifierNode> = createType(Kind.Third)

            private fun createType(expectedKind: Kind): ModifierNodeType<TestModifierElement, TestModifierNode> =
                ModifierNodeType(
                    elementClass = TestModifierElement::class,
                    nodeClass = TestModifierNode::class,
                    validateLocal = { element ->
                        require(element.kind === expectedKind) { "Modifier token and kind do not match." }
                        require(element.invalid.not()) { "Modifier description is invalid." }
                    },
                    createNode = { element ->
                        element.createFailure?.let { failure -> throw failure }
                        (element.sharedNode ?: TestModifierNode(element.probe, element.id, expectedKind, element.behavior)).also { node ->
                            element.probe.nodes[element.id] = node
                        }
                    },
                    updateNode = { previous, current, node ->
                        current.updateFailure?.let { failure -> throw failure }
                        check(previous.kind === expectedKind)
                        check(current.kind === expectedKind)
                        current.probe.updates += Update(previous.id, current.id)
                        node.behavior = current.behavior
                        current.updateMask
                    },
                )
        }
    }

    /**
     * Retained fixture modifier node with lifecycle, default pass-through, and exclusion behavior.
     */
    internal class TestModifierNode(
        private val probe: Probe,
        val id: Int,
        val kind: Kind,
        behavior: Behavior,
    ) : ModifierNode(),
        PaintNode,
        PointerInputNode,
        SemanticsNode,
        LifecycleNode {
        /**
         * Current behavior after an update.
         */
        var behavior: Behavior = behavior

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize =
            run {
                probe.measureCalls[id] = probe.measureCalls.getOrDefault(id, 0) + 1
                if (behavior === Behavior.ExcludeChild) {
                    constraints.constrain(IntSize(3, 3))
                } else {
                    super.measure(scope, constraints)
                }
            }

        override fun layout(scope: LayoutScope) {
            if (behavior === Behavior.PassThrough) {
                super.layout(scope)
            }
        }

        override fun paint(scope: PaintScope) {
            probe.paintCalls[id] = probe.paintCalls.getOrDefault(id, 0) + 1
            if (behavior === Behavior.ExcludeChild) {
                scope.fillRectangle(
                    IntRect(0, 0, scope.size.width, scope.size.height),
                    ArgbColor(0xFFAA00AA.toInt()),
                )
            }
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            if (behavior === Behavior.ExcludeChild) {
                InputResult.Consumed
            } else {
                InputResult.Ignored
            }

        override fun semantics(scope: SemanticsScope) {
            if (behavior === Behavior.ExcludeChild) {
                scope.emit(Semantics(label = UiText.Literal("modifier-$id")))
            }
        }

        override fun attach() {
            val event = Event.Attach(id)
            probe.events += event
            probe.onEvent?.invoke(event)
            (probe.attachFailures[id] ?: probe.attachFailure)?.let { failure -> throw failure }
        }

        override fun detach() {
            val event = Event.Detach(id)
            probe.events += event
            probe.onEvent?.invoke(event)
            (probe.detachFailures[id] ?: probe.detachFailure)?.let { failure -> throw failure }
        }

        override fun dispose() {
            val event = Event.Dispose(id)
            probe.events += event
            probe.onEvent?.invoke(event)
            probe.onDispose?.invoke()
            (probe.disposeFailures[id] ?: probe.disposeFailure)?.let { failure -> throw failure }
        }

        /**
         * Requests node-local invalidation from focused tests.
         *
         * @param phase the phase to invalidate.
         */
        fun invalidate(phase: DirtyPhase) {
            invalidate(DirtyMask.of(phase))
        }
    }
}
