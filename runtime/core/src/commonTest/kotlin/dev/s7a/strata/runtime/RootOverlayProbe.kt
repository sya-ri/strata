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
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope

/**
 * Independent public-node fixture for callback scopes, exact paint output, and cleanup counts.
 * Each description identity belongs to this probe; callbacks and observations remain test-owned.
 */
internal class RootOverlayProbe(
    val size: IntSize = IntSize(4, 4),
    private val childOffset: IntOffset = IntOffset.Zero,
    private val transform: ChildTransform = ChildTransform.Identity,
) {
    var paint: (PaintScope) -> Unit = { }
    var overlay: (PaintScope) -> Unit = { }
    var rootOverlay: (RootOverlayPaintScope) -> Unit = { }
    val scopes: MutableList<RootOverlayPaintScope> = ArrayList()
    var paintCalls: Int = 0
    var overlayCalls: Int = 0
    var rootOverlayCalls: Int = 0
    var detachCalls: Int = 0
    var disposeCalls: Int = 0
    var detachFailure: Throwable? = null
    var disposeFailure: Throwable? = null
    private lateinit var node: ProbeNode

    /**
     * Creates an immutable description with fresh node ownership and this probe's stable key.
     */
    fun element(children: List<Element> = emptyList()): Element = ProbeElement(this, children)

    /**
     * Invalidates the bound node's painting on the tree's owner between frames.
     */
    fun invalidatePaint() {
        node.invalidatePaint()
    }

    /**
     * Keyed fixture description; changing probes causes ordinary replacement and cleanup.
     */
    private class ProbeElement(
        val probe: RootOverlayProbe,
        children: List<Element>,
    ) : Element(ElementIdentity.Keyed(ElementKey(probe)), TYPE, children) {
        companion object {
            val TYPE: ElementType<ProbeElement, ProbeNode> =
                ElementType(
                    elementClass = ProbeElement::class,
                    nodeClass = ProbeNode::class,
                    validateLocal = { },
                    createNode = { element -> ProbeNode(element.probe).also { element.probe.node = it } },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Exercises ordinary, post-child, and root painting through the public capabilities.
     */
    private class ProbeNode(
        private val probe: RootOverlayProbe,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ChildTransformNode,
        ClipChildrenNode,
        PaintNode,
        OverlayPaintNode,
        RootOverlayPaintNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints())
            return constraints.constrain(probe.size)
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) scope.placeChild(index, probe.childOffset)
        }

        override fun childTransform(index: Int): ChildTransform = probe.transform

        override fun paint(scope: PaintScope) {
            probe.paintCalls++
            probe.paint(scope)
        }

        override fun paintOverlay(scope: PaintScope) {
            probe.overlayCalls++
            probe.overlay(scope)
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            probe.rootOverlayCalls++
            probe.scopes.add(scope)
            probe.rootOverlay(scope)
        }

        override fun attach() = Unit

        override fun detach() {
            probe.detachCalls++
            probe.detachFailure?.let { throw it }
        }

        override fun dispose() {
            probe.disposeCalls++
            probe.disposeFailure?.let { throw it }
        }

        /**
         * Marks painting dirty through the protected public node SPI.
         */
        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }
}
