package dev.s7a.strata.runtime.web

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Immutable native presentation description; node allocation assigns stable identity independently of reevaluation.
 * The supplied allocator belongs to one host and contains no application state or callback.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class WebPrimitiveElement(
    val presentation: WebPresentation,
    val naturalSize: IntSize,
    val allocateIdentity: () -> Int,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPES.getValue(presentation.kind), modifier = modifier) {
    /**
     * Retains one DOM identity, updating immutable presentation and releasing text at disposal.
     */
    internal class Node(
        initial: WebPrimitiveElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        SemanticsNode,
        LifecycleNode {
        private val identity = initial.allocateIdentity()
        private var presentation: WebPresentation? = initial.presentation.copy(identity = identity)
        private var naturalSize = initial.naturalSize

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(naturalSize)

        override fun paint(scope: PaintScope) {
            val size = scope.size
            if (0 < size.width && 0 < size.height) {
                scope.drawPlatform(checkNotNull(presentation), IntRect(0, 0, size.width, size.height))
            }
        }

        override fun semantics(scope: SemanticsScope) {
            val current = checkNotNull(presentation)
            scope.emit(Semantics(label = UiText.Literal(current.label), role = current.kind.role, disabled = current.enabled.not()))
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            presentation = null
        }

        /**
         * Applies a reconciled snapshot on the owner agent without changing the DOM identity.
         */
        fun update(current: WebPrimitiveElement): DirtyMask {
            val next = current.presentation.copy(identity = identity)
            if (presentation == next && naturalSize == current.naturalSize) return DirtyMask.None
            presentation = next
            naturalSize = current.naturalSize
            return DirtyMask.All
        }
    }

    /**
     * Owns the immutable element-kind token shared across host evaluations.
     */
    companion object {
        private val TYPES =
            WebPresentation.Kind.entries.associateWith {
                ElementType(
                    elementClass = WebPrimitiveElement::class,
                    nodeClass = Node::class,
                    validateLocal = { _ -> },
                    createNode = { element -> Node(element) },
                    updateNode = { _, current, node -> node.update(current) },
                )
            }
    }
}
