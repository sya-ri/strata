package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Clips one independently measured test child through the public retained primitive capability.
 *
 * The immutable description owns only its positive viewport and child description; its node owns no native resource or input state.
 * Measurement and layout run on the tree thread and propagate invalid constraints or child failures unchanged.
 * The child keeps its requested full extent and zero local origin, making native clip pixels independent of Minecraft Scroll chrome.
 */
private class MinecraftCanvasClipElement(
    private val viewport: IntSize,
    child: Element,
) : Element(ElementIdentity.Positional, TYPE, listOf(child)) {
    private class ClipNode(
        var viewport: IntSize,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ClipChildrenNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 1) { "A Canvas test clip requires one direct child." }
            scope.measureChild(0, Constraints())
            return constraints.constrain(viewport)
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset.Zero)
        }
    }

    private companion object {
        val TYPE: ElementType<MinecraftCanvasClipElement, ClipNode> =
            ElementType(
                elementClass = MinecraftCanvasClipElement::class,
                nodeClass = ClipNode::class,
                validateLocal = { require(0 < it.viewport.width && 0 < it.viewport.height) { "A Canvas test clip requires a positive viewport." } },
                createNode = { ClipNode(it.viewport) },
                updateNode = { previous, current, node ->
                    if (previous.viewport == current.viewport) {
                        DirtyMask.None
                    } else {
                        node.viewport = current.viewport
                        DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Layout, DirtyPhase.Paint)
                    }
                },
            )
    }
}

/**
 * Emits a test-only public-SPI clip viewport without profile-backed Scroll background, padding, or minimum-height rules.
 *
 * [content] runs synchronously in the current profile evaluation and must emit exactly one root.
 * The active scope receives an immutable description; no scope, callback, screen, or native target survives declaration.
 * Invalid extent, root cardinality, or callback failures propagate unchanged to the owning screen.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun UiScope.canvasTestClip(
    size: IntSize,
    content: UiScope.() -> Unit,
) {
    element(MinecraftCanvasClipElement(size, evaluateComponentTree(content)))
}
