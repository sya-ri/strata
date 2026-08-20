package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * Test-owned public-contract primitive and observations for the common Minecraft host.
 *
 * Each content evaluation creates a fresh element whose runtime hook creates a fresh node.
 * Configured callback failures are thrown as their exact supplied instances after the corresponding observation is recorded.
 */
internal class MinecraftHostProbe(
    private val paintFailure: Throwable? = null,
    private val inputFailure: Throwable? = null,
    private val detachFailure: Throwable? = null,
    private val disposeFailure: Throwable? = null,
) {
    /**
     * Exact measurement constraints observed in callback order.
     */
    internal val constraints: MutableList<Constraints> = ArrayList()

    /**
     * Fresh retained nodes created by independent hosts.
     */
    internal val nodes: MutableList<ProbeNode> = ArrayList()

    /**
     * Lifecycle callbacks observed in exact order.
     */
    internal val lifecycle: MutableList<LifecycleStage> = ArrayList()

    /**
     * Number of paint callbacks observed.
     */
    internal var paintCalls: Int = 0

    /**
     * Number of pointer callbacks observed.
     */
    internal var inputCalls: Int = 0

    /**
     * Creates one immutable element description backed by this probe.
     *
     * @return a fresh description that creates a fresh retained node when installed.
     */
    internal fun element(): Element = ProbeElement(this)

    /**
     * Typed lifecycle stages emitted by the fixture.
     */
    internal enum class LifecycleStage {
        /**
         * Node attachment acquired its active resources.
         */
        Attach,

        /**
         * Node attachment released its active resources.
         */
        Detach,

        /**
         * Node ownership released its terminal resources.
         */
        Dispose,
    }

    /**
     * Retained node implementing every pipeline used by the host acceptance tests.
     *
     * The runtime owns this node from creation through terminal disposal on the host owner thread.
     */
    internal class ProbeNode(
        private val probe: MinecraftHostProbe,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        PointerInputNode,
        SemanticsNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "The host probe must remain a leaf." }
            probe.constraints += constraints
            return constraints.constrain(IntSize(2, 1))
        }

        override fun layout(scope: LayoutScope) {
            check(scope.childCount == 0) { "The host probe must remain a leaf." }
        }

        override fun paint(scope: PaintScope) {
            probe.paintCalls += 1
            probe.paintFailure?.let { failure -> throw failure }
            scope.fillRectangle(
                IntRect(0, 0, scope.size.width, scope.size.height),
                ArgbColor(0xFF22D3EE.toInt()),
            )
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            probe.inputCalls += 1
            probe.inputFailure?.let { failure -> throw failure }
            return InputResult.Consumed
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = UiText.Literal("minecraft-host")))
        }

        override fun attach() {
            probe.lifecycle += LifecycleStage.Attach
        }

        override fun detach() {
            probe.lifecycle += LifecycleStage.Detach
            probe.detachFailure?.let { failure -> throw failure }
        }

        override fun dispose() {
            probe.lifecycle += LifecycleStage.Dispose
            probe.disposeFailure?.let { failure -> throw failure }
        }

        /**
         * Invalidates only this node's retained paint payload on its owner thread.
         */
        internal fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    private class ProbeElement(
        private val probe: MinecraftHostProbe,
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
        ) {
        private companion object {
            val TYPE: ElementType<ProbeElement, ProbeNode> =
                ElementType(
                    elementClass = ProbeElement::class,
                    nodeClass = ProbeNode::class,
                    validateLocal = {},
                    createNode = { element ->
                        ProbeNode(element.probe).also { node -> element.probe.nodes += node }
                    },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
