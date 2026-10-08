package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope

/**
 * Shared collection TCK fixture with passive nodes, arbitrary immutable payloads and retained callback scopes.
 */
internal class SemanticsCollectionFixture {
    /**
     * Terminal callbacks in actual reverse-sibling descendant-first order.
     */
    val disposed = ArrayList<Int>()

    /**
     * Creates one owner-local node with or without the semantic capability.
     */
    fun node(
        id: Int,
        values: List<Semantics> = emptyList(),
        participates: Boolean = true,
    ): FixtureNode = if (participates) Participant(this, id, values) else FixtureNode(this, id, values)

    /**
     * Builds a fixed keyed declaration using a session-local node and explicit effective modifier ancestry.
     */
    fun element(
        node: FixtureNode,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
    ): FixtureElement = FixtureElement(node, children, modifier)

    /**
     * Test-owned primitive with fixed child geometry, current callback counters and terminal observations.
     */
    open class FixtureNode(
        private val fixture: SemanticsCollectionFixture,
        val id: Int,
        var values: List<Semantics>,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        PointerInputNode,
        LifecycleNode {
        /**
         * Current semantic callback count.
         */
        var callbacks = 0

        /**
         * Scope captured only by the test to verify its terminal lifetime.
         */
        var scope: SemanticsScope? = null

        /**
         * Optional test callback replaces the default payload emission.
         */
        var emit: ((SemanticsScope) -> Unit)? = null

        /**
         * Optional selected child indices; all measured children participate when null.
         */
        var placedIndices: Set<Int>? = null

        /**
         * Pointer callback observations independent of semantic participation.
         */
        var inputs = 0

        /**
         * Optional terminal failure after recording disposal.
         */
        var disposeFailure: Throwable? = null

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints.fixed(1, 1))
            return constraints.constrain(IntSize(scope.childCount + 1, 1))
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) {
                val selected = placedIndices
                if (selected == null || index in selected) scope.placeChild(index, IntOffset(index + 1, 0))
            }
        }

        override fun paint(scope: PaintScope) = Unit

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            inputs += 1
            return InputResult.Consumed
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            fixture.disposed.add(id)
            values = emptyList()
            emit = null
            disposeFailure?.let { throw it }
        }

        /**
         * Invalidates only the requested phase through the real retained owner.
         */
        fun invalidatePhase(phase: DirtyPhase) {
            invalidate(DirtyMask.of(phase))
        }
    }

    /**
     * Capable fixture preserving a real scope even when it emits no values.
     */
    private class Participant(
        fixture: SemanticsCollectionFixture,
        id: Int,
        values: List<Semantics>,
    ) : FixtureNode(fixture, id, values),
        SemanticsNode {
        override fun semantics(scope: SemanticsScope) {
            callbacks += 1
            this.scope = scope
            val callback = emit
            if (callback == null) values.forEach(scope::emit) else callback(scope)
        }
    }

    /**
     * Test declaration whose child membership can change without replacing its current keyed node.
     */
    class FixtureElement(
        val node: FixtureNode,
        children: List<Element>,
        modifier: Modifier,
    ) : Element(ElementIdentity.Keyed(ElementKey(node.id)), TYPE, children = children, modifier = modifier) {
        /**
         * Shared token; fixture nodes remain caller-owned only until adopted by one retained tree.
         */
        companion object {
            val TYPE: ElementType<FixtureElement, FixtureNode> =
                ElementType(
                    elementClass = FixtureElement::class,
                    nodeClass = FixtureNode::class,
                    validateLocal = { _ -> },
                    createNode = { it.node },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
