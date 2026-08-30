package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameCutoffNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.concurrent.atomic.AtomicLong
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Immutable, platform-neutral description of one input-passive canvas.
 *
 * The retained node owns its attachment binding on the tree thread, while [source] remains externally owned.
 * Source acquisition and cleanup failures propagate through the ordinary retained lifecycle.
 *
 * @param source immutable externally owned source description.
 * @param destinationSize positive exact logical destination size.
 * @param modifier ordered active behavior.
 * @param key optional stable sibling identity.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class CanvasElement(
    private val source: CanvasSource,
    private val destinationSize: IntSize,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    private class Node(
        private var source: CanvasSource?,
        private var destinationSize: IntSize,
        private val canvasId: CanvasId,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        FrameCutoffNode,
        SessionAttachmentNode {
        private var active: Boolean = false
        private var binding: CanvasBinding? = null

        override fun attach() {
            sessionAttached()
        }

        override fun sessionAttached() {
            if (active) return
            active = true
            binding = checkNotNull(source) { "A disposed canvas cannot attach." }.open(canvasId)
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        override fun sessionDetached() {
            active = false
            releaseBinding()
        }

        override fun detach() {
            sessionDetached()
        }

        override fun dispose() {
            source = null
            sessionDetached()
        }

        override fun captureFrameState() {
            binding?.captureFrame()
        }

        override fun commitFrameState() {
            if (binding?.commitFrame() == true) invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(destinationSize)) { "Canvas constraints must contain its requested size." }
            return destinationSize
        }

        override fun paint(scope: PaintScope) {
            binding?.paint(scope)
        }

        /**
         * Reconciles immutable source identity and logical extent on the owner thread.
         * Source replacement releases the previous attachment before opening its replacement and propagates acquisition or cleanup failures unchanged.
         *
         * @param element replacement description whose source remains externally owned.
         * @return the minimal affected retained phases.
         */
        fun update(element: CanvasElement): DirtyMask {
            val sizeChanged = destinationSize != element.destinationSize
            val sourceChanged = source !== element.source
            destinationSize = element.destinationSize
            if (sourceChanged) {
                source = element.source
                releaseBinding()
                if (active) binding = element.source.open(canvasId)
            }
            return when {
                sizeChanged -> DirtyMask.of(DirtyPhase.Measure)
                sourceChanged -> DirtyMask.of(DirtyPhase.Paint)
                else -> DirtyMask.None
            }
        }

        private fun releaseBinding() {
            val previous = binding
            binding = null
            previous?.close()
        }
    }

    private companion object {
        private val nextIdentity: AtomicLong = AtomicLong()
        val TYPE: ElementType<CanvasElement, Node> =
            ElementType(
                elementClass = CanvasElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(0 < element.destinationSize.width && 0 < element.destinationSize.height) {
                        "Canvas destination dimensions must be positive."
                    }
                },
                createNode = { element ->
                    Node(element.source, element.destinationSize, CanvasId(nextIdentity.updateAndGet(Math::incrementExact)))
                },
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
