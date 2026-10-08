package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** A fixture-only immutable child translation used to generate raw fractional clips through the real retained core. */
private class MinecraftCanvasTransformElement(
    private val offset: Double,
    child: Element,
) : Element(ElementIdentity.Positional, TYPE, listOf(child)) {
    private class TransformNode(
        private val offset: Double,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ChildTransformNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = scope.measureChild(0, constraints)

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset.Zero)
        }

        override fun childTransform(index: Int): ChildTransform = ChildTransform(1.0, DoubleOffset(offset, offset))
    }

    private companion object {
        val TYPE: ElementType<MinecraftCanvasTransformElement, TransformNode> =
            ElementType(
                elementClass = MinecraftCanvasTransformElement::class,
                nodeClass = TransformNode::class,
                validateLocal = { require(it.offset.isFinite()) },
                createNode = { TransformNode(it.offset) },
                updateNode = { previous, current, _ ->
                    check(previous.offset == current.offset) { "The frozen transform fixture never changes layout." }
                    DirtyMask.None
                },
            )
    }
}

/** Emits exactly one child under a fixed translation, with no retained callback or native ownership. */
@OptIn(InternalStrataRuntimeApi::class)
internal fun UiScope.canvasTestTransform(
    offset: Double,
    content: UiScope.() -> Unit,
) {
    element(MinecraftCanvasTransformElement(offset, evaluateComponentTree(content)))
}
