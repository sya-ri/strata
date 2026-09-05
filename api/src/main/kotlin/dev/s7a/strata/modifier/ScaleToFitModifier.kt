package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode

/**
 * Internal implementation of uniform scale-to-fit behavior.
 */
internal object ScaleToFitModifier {
    /**
     * Immutable scale-to-fit description.
     *
     * @property contentSize positive fixed coordinate extent used to measure the virtual child.
     * @property contentAlignment placement of uniformly scaled content inside the modifier's measured bounds.
     * @property allowUpscaling whether a fitting scale greater than one may enlarge the content.
     */
    internal data class Element(
        val contentSize: IntSize,
        val contentAlignment: Alignment,
        val allowUpscaling: Boolean,
    ) : ModifierElement {
        /**
         * The stable scale-to-fit modifier token.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained modifier node that measures one child in fixed content coordinates and publishes its fitted transform.
     *
     * The runtime owns the node on one tree thread after creation.
     * It retains only immutable geometry policy and its latest resolved transform, owns no external resources, and reports validation or child failures through the active tree operation.
     *
     * @param initial initial immutable scale-to-fit description.
     */
    internal class Node(
        initial: Element,
    ) : ModifierNode(),
        ChildTransformNode {
        private var contentSize: IntSize = initial.contentSize
        private var contentAlignment: Alignment = initial.contentAlignment
        private var allowUpscaling: Boolean = initial.allowUpscaling
        private var transform: ChildTransform = ChildTransform.Identity

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(scope.childCount == 1) { "A scale-to-fit modifier must have exactly one virtual child." }
            scope.measureChild(0, Constraints.fixed(contentSize.width, contentSize.height))
            return constraints.constrain(contentSize)
        }

        override fun layout(scope: LayoutScope) {
            require(scope.childCount == 1) { "A scale-to-fit modifier must have exactly one virtual child." }
            val widthScale = scope.size.width.toDouble() / contentSize.width.toDouble()
            val heightScale = scope.size.height.toDouble() / contentSize.height.toDouble()
            val fittingScale = minOf(widthScale, heightScale)
            if (fittingScale == 0.0) {
                transform = ChildTransform.Identity
                return
            }
            scope.placeChild(0, IntOffset.Zero)
            val scale = if (allowUpscaling) fittingScale else minOf(1.0, fittingScale)
            val horizontalSlack =
                (scope.size.width.toDouble() - contentSize.width.toDouble() * scale).coerceAtLeast(0.0)
            val verticalSlack =
                (scope.size.height.toDouble() - contentSize.height.toDouble() * scale).coerceAtLeast(0.0)
            transform =
                ChildTransform(
                    scale = scale,
                    offset =
                        DoubleOffset(
                            x = horizontalOffset(contentAlignment.horizontalAlignment, horizontalSlack),
                            y = verticalOffset(contentAlignment.verticalAlignment, verticalSlack),
                        ),
                )
        }

        override fun childTransform(index: Int): ChildTransform {
            require(index == 0) { "A scale-to-fit modifier exposes only virtual child index zero." }
            return transform
        }

        /**
         * Applies one validated immutable description to this retained node.
         *
         * The call runs on the owning tree thread, retains no caller-owned mutable state, and performs no external work.
         *
         * @param current incoming validated description.
         * @return measurement invalidation for changed content size, layout invalidation for another policy change, or no invalidation for an equal update.
         */
        internal fun update(current: Element): DirtyMask {
            val contentSizeChanged = contentSize != current.contentSize
            val layoutChanged =
                contentAlignment != current.contentAlignment ||
                    allowUpscaling != current.allowUpscaling
            contentSize = current.contentSize
            contentAlignment = current.contentAlignment
            allowUpscaling = current.allowUpscaling
            return when {
                contentSizeChanged -> DirtyMask.of(DirtyPhase.Measure)
                layoutChanged -> DirtyMask.of(DirtyPhase.Layout)
                else -> DirtyMask.None
            }
        }

        private fun horizontalOffset(
            alignment: HorizontalAlignment,
            slack: Double,
        ): Double =
            when (alignment) {
                HorizontalAlignment.Start -> 0.0
                HorizontalAlignment.Center -> slack / 2.0
                HorizontalAlignment.End -> slack
            }

        private fun verticalOffset(
            alignment: VerticalAlignment,
            slack: Double,
        ): Double =
            when (alignment) {
                VerticalAlignment.Top -> 0.0
                VerticalAlignment.Center -> slack / 2.0
                VerticalAlignment.Bottom -> slack
            }
    }

    /**
     * Stable token shared by every scale-to-fit modifier description.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element ->
                require(0 < element.contentSize.width && 0 < element.contentSize.height) {
                    "Scale-to-fit content dimensions must be positive."
                }
            },
            createNode = ::Node,
            updateNode = { _, current, node -> node.update(current) },
        )
}
